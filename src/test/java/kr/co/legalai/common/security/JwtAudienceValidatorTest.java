package kr.co.legalai.common.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;

class JwtAudienceValidatorTest {
    private final JwtAudienceValidator validator = new JwtAudienceValidator("better-call-ai");

    @Test
    void acceptsExpectedAudience() {
        assertFalse(validator.validate(jwt(List.of("better-call-ai"))).hasErrors());
    }

    @Test
    void rejectsMissingAudience() {
        assertFalse(validator.validate(jwt(List.of("another-api"))).getErrors().isEmpty());
    }

    private Jwt jwt(List<String> audience) {
        Instant now = Instant.now();
        return Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject("11111111-1111-1111-1111-111111111111")
                .audience(audience)
                .issuedAt(now)
                .expiresAt(now.plusSeconds(300))
                .build();
    }
}
