package kr.co.legalai.auth.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;

@Component
public class JwtTokenService {
    private final SecureRandom secureRandom = new SecureRandom();
    private final JwtKeyProvider keyProvider;
    private final String issuer;
    private final String audience;
    private final Duration accessTokenTtl;
    private final Duration refreshTokenTtl;

    public JwtTokenService(
            JwtKeyProvider keyProvider,
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuer,
            @Value("${security.jwt.audience}") String audience,
            @Value("${security.jwt.access-token-ttl}") Duration accessTokenTtl,
            @Value("${security.jwt.refresh-token-ttl}") Duration refreshTokenTtl
    ) {
        this.keyProvider = keyProvider;
        this.issuer = issuer;
        this.audience = audience;
        this.accessTokenTtl = requirePositive(accessTokenTtl, "Access Token 만료시간");
        this.refreshTokenTtl = requirePositive(refreshTokenTtl, "Refresh Token 만료시간");
    }

    public AccessToken createAccessToken(UUID userId, Instant now) {
        Instant expiresAt = now.plus(accessTokenTtl);
        var signingKey = keyProvider.getSigningKey();
        var claims = new JWTClaimsSet.Builder()
                .jwtID(UUID.randomUUID().toString())
                .subject(userId.toString())
                .issuer(issuer)
                .audience(audience)
                .issueTime(Date.from(now))
                .notBeforeTime(Date.from(now))
                .expirationTime(Date.from(expiresAt))
                .claim("token_type", "access")
                .build();
        var jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signingKey.getKeyID()).build(),
                claims
        );
        try {
            jwt.sign(new RSASSASigner(signingKey.toRSAPrivateKey()));
            return new AccessToken(jwt.serialize(), expiresAt);
        } catch (Exception exception) {
            throw new IllegalStateException("Access Token을 생성할 수 없습니다.", exception);
        }
    }

    public RefreshToken createRefreshToken(Instant now) {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return new RefreshToken(
                Base64.getUrlEncoder().withoutPadding().encodeToString(bytes),
                now.plus(refreshTokenTtl)
        );
    }

    public record AccessToken(String value, Instant expiresAt) {
    }

    public record RefreshToken(String value, Instant expiresAt) {
    }

    private Duration requirePositive(Duration duration, String name) {
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(name + "은 0보다 커야 합니다.");
        }
        return duration;
    }
}
