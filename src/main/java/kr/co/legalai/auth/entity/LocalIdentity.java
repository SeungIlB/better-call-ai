package kr.co.legalai.auth.entity;

import java.util.UUID;

public record LocalIdentity(
        UUID userId,
        byte[] encryptedEmail,
        String encryptionKeyId,
        String displayName,
        String userStatus,
        String passwordHash,
        String identityStatus
) {
    public boolean isActive() {
        return "active".equals(userStatus) && "active".equals(identityStatus);
    }
}
