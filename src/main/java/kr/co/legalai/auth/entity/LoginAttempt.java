package kr.co.legalai.auth.entity;

import java.time.Instant;

public record LoginAttempt(int failedAttempts, Instant lockedUntil) {
    public boolean isLocked(Instant now) {
        return lockedUntil != null && lockedUntil.isAfter(now);
    }
}
