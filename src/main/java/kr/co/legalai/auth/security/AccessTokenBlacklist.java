package kr.co.legalai.auth.security;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** 로그아웃된 Access Token의 남은 수명만 Redis에 보관한다. Redis 장애 시 JWT 자체 검증으로 계속 진행한다. */
@Component
public class AccessTokenBlacklist {
    private static final String PREFIX = "better-call-ai:jwt:blacklist:";
    private final StringRedisTemplate redis;

    public AccessTokenBlacklist(StringRedisTemplate redis) { this.redis = redis; }

    public void revoke(String jti, Duration ttl) {
        if (jti == null || jti.isBlank() || ttl.isNegative() || ttl.isZero()) return;
        try { redis.opsForValue().set(PREFIX + jti, "1", ttl); } catch (RuntimeException ignored) { }
    }

    public boolean isRevoked(String jti) {
        if (jti == null || jti.isBlank()) return false;
        try { return Boolean.TRUE.equals(redis.hasKey(PREFIX + jti)); } catch (RuntimeException ignored) { return false; }
    }
}
