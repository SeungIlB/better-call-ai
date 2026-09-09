package kr.co.legalai.auth.repository;

import kr.co.legalai.auth.entity.LocalIdentity;
import kr.co.legalai.auth.entity.LoginAttempt;
import kr.co.legalai.auth.entity.RefreshTokenEntity;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;

@Repository
public class AuthRepository {
    private final JdbcTemplate jdbcTemplate;

    public AuthRepository(@Qualifier("authJdbcTemplate") JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public LoginAttempt lockLoginAttempt(String emailHash) {
        jdbcTemplate.update("""
                INSERT INTO identity.login_attempts(email_lookup_hash) VALUES (?)
                ON CONFLICT (email_lookup_hash) DO NOTHING
                """, emailHash);
        return jdbcTemplate.queryForObject("""
                SELECT failed_attempts, locked_until FROM identity.login_attempts
                WHERE email_lookup_hash = ? FOR UPDATE
                """, (result, rowNumber) -> new LoginAttempt(
                result.getInt("failed_attempts"),
                result.getTimestamp("locked_until") == null
                        ? null : result.getTimestamp("locked_until").toInstant()
        ), emailHash);
    }

    public void updateLoginAttempt(String emailHash, int failures, Instant lockedUntil) {
        jdbcTemplate.update("""
                UPDATE identity.login_attempts
                SET failed_attempts = ?, locked_until = ?, updated_at = clock_timestamp()
                WHERE email_lookup_hash = ?
                """, failures, lockedUntil == null ? null : Timestamp.from(lockedUntil), emailHash);
    }

    public void lockUser(UUID userId) {
        // 로그인·재발급·폐기는 사용자 행을 먼저 잠근 뒤 토큰 행을 잠근다.
        jdbcTemplate.query("SELECT id FROM identity.users WHERE id = ? FOR UPDATE",
                (result, rowNumber) -> result.getObject("id", UUID.class), userId);
    }

    public Optional<UUID> findRefreshTokenOwner(String tokenHash) {
        return jdbcTemplate.query("SELECT user_id FROM identity.refresh_tokens WHERE token_hash = ?",
                (result, rowNumber) -> result.getObject("user_id", UUID.class), tokenHash)
                .stream().findFirst();
    }

    public Optional<LocalIdentity> findLocalByEmailHash(String emailHash) {
        return jdbcTemplate.query("""
                        SELECT u.id, u.email_enc, u.encryption_key_id, u.display_name,
                               u.status AS user_status, a.password_hash,
                               a.status AS identity_status
                        FROM identity.users u
                        JOIN identity.auth_identities a ON a.user_id = u.id
                        WHERE u.email_lookup_hash = ? AND a.provider = 'local'
                        """,
                (result, rowNumber) -> new LocalIdentity(
                        result.getObject("id", UUID.class),
                        result.getBytes("email_enc"),
                        result.getString("encryption_key_id"),
                        result.getString("display_name"),
                        result.getString("user_status"),
                        result.getString("password_hash"),
                        result.getString("identity_status")
                ),
                emailHash
        ).stream().findFirst();
    }

    public Optional<LocalIdentity> findLocalByUserId(UUID userId) {
        return jdbcTemplate.query("""
                        SELECT u.id, u.email_enc, u.encryption_key_id, u.display_name,
                               u.status AS user_status, a.password_hash,
                               a.status AS identity_status
                        FROM identity.users u
                        JOIN identity.auth_identities a ON a.user_id = u.id
                        WHERE u.id = ? AND a.provider = 'local'
                        """,
                (result, rowNumber) -> new LocalIdentity(
                        result.getObject("id", UUID.class),
                        result.getBytes("email_enc"),
                        result.getString("encryption_key_id"),
                        result.getString("display_name"),
                        result.getString("user_status"),
                        result.getString("password_hash"),
                        result.getString("identity_status")
                ),
                userId
        ).stream().findFirst();
    }

    public void saveUser(
            UUID userId,
            byte[] encryptedEmail,
            String emailHash,
            String encryptionKeyId,
            String displayName
    ) {
        jdbcTemplate.update("""
                        INSERT INTO identity.users(
                            id, email_enc, email_lookup_hash, encryption_key_id, display_name
                        ) VALUES (?, ?, ?, ?, ?)
                        """,
                userId,
                encryptedEmail,
                emailHash,
                encryptionKeyId,
                displayName.trim()
        );
    }

    public void saveLocalIdentity(UUID userId, String emailHash, String passwordHash) {
        jdbcTemplate.update("""
                        INSERT INTO identity.auth_identities(
                            user_id, provider, provider_subject, password_hash
                        ) VALUES (?, 'local', ?, ?)
                        """,
                userId,
                emailHash,
                passwordHash
        );
    }

    public void saveRequiredConsents(UUID userId) {
        jdbcTemplate.batchUpdate("""
                        INSERT INTO identity.user_consents(
                            user_id, consent_type, policy_version, granted, granted_at
                        ) VALUES (?, ?, 'v1', true, now())
                        """,
                java.util.List.of(
                        new Object[]{userId, "terms"},
                        new Object[]{userId, "privacy"}
                )
        );
    }

    public void updateLastLogin(UUID userId) {
        jdbcTemplate.update("""
                        UPDATE identity.users SET last_login_at = now() WHERE id = ?
                        """,
                userId
        );
        jdbcTemplate.update("""
                        UPDATE identity.auth_identities
                        SET last_used_at = now()
                        WHERE user_id = ? AND provider = 'local'
                        """,
                userId
        );
    }

    public void saveRefreshToken(
            UUID tokenId,
            UUID userId,
            String tokenHash,
            Instant expiresAt
    ) {
        jdbcTemplate.update("""
                        INSERT INTO identity.refresh_tokens(id, user_id, token_hash, expires_at)
                        VALUES (?, ?, ?, ?)
                        """,
                tokenId,
                userId,
                tokenHash,
                Timestamp.from(expiresAt)
        );
    }

    public Optional<RefreshTokenEntity> findRefreshTokenForUpdate(String tokenHash) {
        return jdbcTemplate.query("""
                        SELECT id, user_id, expires_at, revoked_at, replaced_by_token_id
                        FROM identity.refresh_tokens
                        WHERE token_hash = ?
                        FOR UPDATE
                        """,
                (result, rowNumber) -> new RefreshTokenEntity(
                        result.getObject("id", UUID.class),
                        result.getObject("user_id", UUID.class),
                        result.getTimestamp("expires_at").toInstant(),
                        result.getTimestamp("revoked_at") == null
                                ? null : result.getTimestamp("revoked_at").toInstant(),
                        result.getObject("replaced_by_token_id", UUID.class)
                ),
                tokenHash
        ).stream().findFirst();
    }

    public void replaceRefreshToken(UUID currentId, UUID replacementId, Instant revokedAt) {
        jdbcTemplate.update("""
                        UPDATE identity.refresh_tokens
                        SET revoked_at = ?, replaced_by_token_id = ?
                        WHERE id = ? AND revoked_at IS NULL
                        """,
                Timestamp.from(revokedAt),
                replacementId,
                currentId
        );
    }

    public void revokeRefreshToken(UUID tokenId, Instant revokedAt) {
        jdbcTemplate.update("""
                        UPDATE identity.refresh_tokens
                        SET revoked_at = COALESCE(revoked_at, ?)
                        WHERE id = ?
                        """,
                Timestamp.from(revokedAt),
                tokenId
        );
    }

    public void revokeAllActiveRefreshTokens(UUID userId, Instant revokedAt) {
        jdbcTemplate.update("""
                        UPDATE identity.refresh_tokens
                        SET revoked_at = ?
                        WHERE user_id = ? AND revoked_at IS NULL
                        """,
                Timestamp.from(revokedAt),
                userId
        );
    }
}
