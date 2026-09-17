package kr.co.legalai.common.security;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

@Component
public class AuthenticatedUser {
    private final JdbcTemplate authJdbc;

    public AuthenticatedUser() { this.authJdbc = null; }

    public AuthenticatedUser(@Qualifier("authJdbcTemplate") JdbcTemplate authJdbc) { this.authJdbc = authJdbc; }

    public UUID getUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication.getName() == null) {
            throw new AccessDeniedException("Authentication is required");
        }
        try {
            return UUID.fromString(authentication.getName());
        } catch (IllegalArgumentException exception) {
            throw new AccessDeniedException("JWT subject must be a UUID");
        }
    }

    public void requireMaster() {
        UUID userId = getUserId();
        if (authJdbc == null || !Boolean.TRUE.equals(authJdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM identity.users WHERE id=? AND account_role='MASTER' AND status='active')",
                Boolean.class, userId))) {
            throw new org.springframework.security.access.AccessDeniedException("Master permission is required");
        }
    }
}
