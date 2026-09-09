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
        return required(transaction.execute(status -> {
            LocalIdentity identity = repository.findLocalByEmailHash(emailHash).orElse(null);
            String storedHash = identity == null ? dummyPasswordHash : identity.passwordHash();
            boolean passwordMatches = passwordEncoder.matches(request.password(), storedHash);
            if (identity == null || !passwordMatches) {
                throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
            }
            if (!identity.isActive()) {
                throw new BusinessException(ErrorCode.ACCOUNT_UNAVAILABLE);
            }
            repository.updateLastLogin(identity.userId());
            return issueTokenPair(identity.userId(), Instant.now());
        }));
    }

    @Override
    public AuthTokenResponse refresh(String refreshToken) {
        RefreshOutcome outcome = required(transaction.execute(status -> rotate(refreshToken, Instant.now())));
        if (outcome.errorCode() != null) {
            throw new BusinessException(outcome.errorCode());
        }
        return outcome.response();
    }

    @Override
    public void logout(String refreshToken) {
        UUID userId = authenticatedUser.getUserId();
        required(transaction.execute(status -> {
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

    private RefreshOutcome rotate(String rawToken, Instant now) {
        RefreshTokenEntity current = repository.findRefreshTokenForUpdate(sha256(rawToken)).orElse(null);
        if (current == null) {
            return RefreshOutcome.error(ErrorCode.INVALID_REFRESH_TOKEN);
        }
        if (current.isRevoked()) {
            if (current.replacedByTokenId() != null) {
                repository.revokeAllActiveRefreshTokens(current.userId(), now);
                return RefreshOutcome.error(ErrorCode.REFRESH_TOKEN_REUSED);
            }
            return RefreshOutcome.error(ErrorCode.INVALID_REFRESH_TOKEN);
        }
        if (current.isExpired(now)) {
            repository.revokeRefreshToken(current.id(), now);
            return RefreshOutcome.error(ErrorCode.INVALID_REFRESH_TOKEN);
        }
        LocalIdentity identity = repository.findLocalByUserId(current.userId()).orElse(null);
        if (identity == null || !identity.isActive()) {
            repository.revokeAllActiveRefreshTokens(current.userId(), now);
            return RefreshOutcome.error(ErrorCode.ACCOUNT_UNAVAILABLE);
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
        return RefreshOutcome.success(toResponse(access, replacement));
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

    private record RefreshOutcome(AuthTokenResponse response, ErrorCode errorCode) {
        static RefreshOutcome success(AuthTokenResponse response) {
            return new RefreshOutcome(response, null);
        }

        static RefreshOutcome error(ErrorCode errorCode) {
            return new RefreshOutcome(null, errorCode);
        }
    }
}
