package kr.co.legalai.auth.serviceimpl;

import kr.co.legalai.auth.dto.request.LoginRequest;
import kr.co.legalai.auth.dto.request.RegisterRequest;
import kr.co.legalai.auth.dto.response.AuthTokenResponse;
import kr.co.legalai.auth.dto.response.UserResponse;
import kr.co.legalai.auth.entity.LocalIdentity;
import kr.co.legalai.auth.entity.RefreshTokenEntity;
import kr.co.legalai.auth.repository.AuthRepository;
import kr.co.legalai.auth.security.IdentityCrypto;
import kr.co.legalai.auth.security.JwtTokenService;
import kr.co.legalai.auth.service.AuthService;
import kr.co.legalai.common.exception.BusinessException;
import kr.co.legalai.common.exception.ErrorCode;
import kr.co.legalai.common.security.AuthenticatedUser;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

@Service
public class AuthServiceImpl implements AuthService {
    private final AuthRepository repository;
    private final IdentityCrypto identityCrypto;
    private final JwtTokenService tokenService;
    private final PasswordEncoder passwordEncoder;
    private final TransactionTemplate transaction;
    private final AuthenticatedUser authenticatedUser;
    private final String dummyPasswordHash;

    public AuthServiceImpl(
            AuthRepository repository,
            IdentityCrypto identityCrypto,
            JwtTokenService tokenService,
            PasswordEncoder passwordEncoder,
            @Qualifier("authTransactionTemplate") TransactionTemplate transaction,
            AuthenticatedUser authenticatedUser
    ) {
        this.repository = repository;
        this.identityCrypto = identityCrypto;
        this.tokenService = tokenService;
        this.passwordEncoder = passwordEncoder;
        this.transaction = transaction;
        this.authenticatedUser = authenticatedUser;
        this.dummyPasswordHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    @Override
    public AuthTokenResponse register(RegisterRequest request) {
        String normalizedEmail = identityCrypto.normalizeEmail(request.email());
        String emailHash = identityCrypto.lookupHash(normalizedEmail);
        try {
            return required(transaction.execute(status -> {
                if (repository.findLocalByEmailHash(emailHash).isPresent()) {
                    throw new BusinessException(ErrorCode.EMAIL_ALREADY_REGISTERED);
                }
                UUID userId = UUID.randomUUID();
                repository.saveUser(
                        userId,
                        identityCrypto.encryptEmail(normalizedEmail),
                        emailHash,
                        identityCrypto.encryptionKeyId(),
                        request.displayName()
                );
                repository.saveLocalIdentity(
                        userId,
                        emailHash,
                        passwordEncoder.encode(request.password())
                );
                repository.saveRequiredConsents(userId);
                return issueTokenPair(userId, Instant.now());
            }));
        } catch (DuplicateKeyException exception) {
            throw new BusinessException(ErrorCode.EMAIL_ALREADY_REGISTERED);
        }
    }

    @Override
    public AuthTokenResponse login(LoginRequest request) {
        String emailHash = identityCrypto.lookupHash(identityCrypto.normalizeEmail(request.email()));
        TokenOutcome outcome = required(transaction.execute(status -> {
            var attempt = repository.lockLoginAttempt(emailHash);
            Instant now = Instant.now();
            if (attempt.isLocked(now)) {
                return TokenOutcome.error(ErrorCode.LOGIN_RATE_LIMITED);
            }
            LocalIdentity identity = repository.findLocalByEmailHash(emailHash).orElse(null);
            String storedHash = identity == null ? dummyPasswordHash : identity.passwordHash();
            boolean passwordMatches = passwordEncoder.matches(request.password(), storedHash);
            if (identity == null || !passwordMatches) {
                int failures = attempt.lockedUntil() == null ? attempt.failedAttempts() + 1 : 1;
                repository.updateLoginAttempt(emailHash, failures,
                        failures == 5 ? Instant.now().plus(Duration.ofMinutes(15)) : null);
                return TokenOutcome.error(failures == 5
                        ? ErrorCode.LOGIN_RATE_LIMITED : ErrorCode.INVALID_CREDENTIALS);
            }
            repository.lockUser(identity.userId());
            identity = repository.findLocalByUserId(identity.userId()).orElseThrow(
                    () -> new BusinessException(ErrorCode.ACCOUNT_UNAVAILABLE));
            if (!identity.isActive()) {
                return TokenOutcome.error(ErrorCode.ACCOUNT_UNAVAILABLE);
            }
            repository.updateLoginAttempt(emailHash, 0, null);
            repository.updateLastLogin(identity.userId());
            return TokenOutcome.success(issueTokenPair(identity.userId(), Instant.now()));
        }));
        return outcome.result();
    }

    @Override
    public AuthTokenResponse refresh(String refreshToken) {
        return required(transaction.execute(status -> rotate(refreshToken))).result();
    }

    @Override
    public void logout(String refreshToken) {
        UUID userId = authenticatedUser.getUserId();
        required(transaction.execute(status -> {
            repository.lockUser(userId);
            repository.findRefreshTokenForUpdate(sha256(refreshToken))
                    .filter(token -> token.userId().equals(userId))
                    .ifPresent(token -> repository.revokeRefreshToken(token.id(), Instant.now()));
            return Boolean.TRUE;
        }));
    }

    @Override
    public UserResponse getMe() {
        UUID userId = authenticatedUser.getUserId();
        LocalIdentity identity = required(transaction.execute(status ->
                repository.findLocalByUserId(userId)
                        .filter(LocalIdentity::isActive)
                        .orElseThrow(() -> new BusinessException(ErrorCode.ACCOUNT_UNAVAILABLE))
        ));
        return new UserResponse(
                identity.userId(),
                identityCrypto.decryptEmail(identity.encryptedEmail(), identity.encryptionKeyId()),
                identity.displayName()
        );
    }

    private TokenOutcome rotate(String rawToken) {
        String tokenHash = sha256(rawToken);
        UUID userId = repository.findRefreshTokenOwner(tokenHash).orElse(null);
        if (userId == null) {
            return TokenOutcome.error(ErrorCode.INVALID_REFRESH_TOKEN);
        }
        repository.lockUser(userId);
        RefreshTokenEntity current = repository.findRefreshTokenForUpdate(tokenHash).orElse(null);
        Instant now = Instant.now();
        if (current == null) {
            return TokenOutcome.error(ErrorCode.INVALID_REFRESH_TOKEN);
        }
        if (current.isRevoked()) {
            if (current.replacedByTokenId() != null) {
                repository.revokeAllActiveRefreshTokens(current.userId(), now);
                return TokenOutcome.error(ErrorCode.REFRESH_TOKEN_REUSED);
            }
            return TokenOutcome.error(ErrorCode.INVALID_REFRESH_TOKEN);
        }
        if (current.isExpired(now)) {
            repository.revokeRefreshToken(current.id(), now);
            return TokenOutcome.error(ErrorCode.INVALID_REFRESH_TOKEN);
        }
        LocalIdentity identity = repository.findLocalByUserId(current.userId()).orElse(null);
        if (identity == null || !identity.isActive()) {
            repository.revokeAllActiveRefreshTokens(current.userId(), now);
            return TokenOutcome.error(ErrorCode.ACCOUNT_UNAVAILABLE);
        }

        var replacement = tokenService.createRefreshToken(now);
        UUID replacementId = UUID.randomUUID();
        repository.saveRefreshToken(
                replacementId,
                current.userId(),
                sha256(replacement.value()),
                replacement.expiresAt()
        );
        repository.replaceRefreshToken(current.id(), replacementId, now);
        var access = tokenService.createAccessToken(current.userId(), now);
        return TokenOutcome.success(toResponse(access, replacement));
    }

    private AuthTokenResponse issueTokenPair(UUID userId, Instant now) {
        var refresh = tokenService.createRefreshToken(now);
        repository.saveRefreshToken(UUID.randomUUID(), userId, sha256(refresh.value()), refresh.expiresAt());
        var access = tokenService.createAccessToken(userId, now);
        return toResponse(access, refresh);
    }

    private AuthTokenResponse toResponse(
            JwtTokenService.AccessToken access,
            JwtTokenService.RefreshToken refresh
    ) {
        return new AuthTokenResponse(
                "Bearer",
                access.value(),
                access.expiresAt(),
                refresh.value(),
                refresh.expiresAt()
        );
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", exception);
        }
    }

    private <T> T required(T value) {
        return Objects.requireNonNull(value, "인증 트랜잭션 결과가 없습니다.");
    }

    private record TokenOutcome(AuthTokenResponse response, ErrorCode errorCode) {
        static TokenOutcome success(AuthTokenResponse response) {
            return new TokenOutcome(response, null);
        }

        static TokenOutcome error(ErrorCode errorCode) {
            return new TokenOutcome(null, errorCode);
        }

        AuthTokenResponse result() {
            // 실패 횟수와 토큰 폐기를 커밋한 다음 예외를 전달한다.
            if (errorCode != null) {
                throw new BusinessException(errorCode);
            }
            return response;
        }
    }
}
