package kr.co.legalai.auth.entity;

import java.time.Instant;
import java.util.UUID;

public record RefreshTokenEntity(
        UUID id,
        UUID userId,
        Instant expiresAt,
        Instant revokedAt,
        UUID replacedByTokenId
) {
    public boolean isRevoked() {
        return revokedAt != null;
    }

    public boolean isExpired(Instant now) {
        return !expiresAt.isAfter(now);
    }
}
