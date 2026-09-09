package kr.co.legalai.auth.dto.response;

import java.time.Instant;

public record AuthTokenResponse(
        String tokenType,
        String accessToken,
        Instant accessTokenExpiresAt,
        String refreshToken,
        Instant refreshTokenExpiresAt
) {
}
