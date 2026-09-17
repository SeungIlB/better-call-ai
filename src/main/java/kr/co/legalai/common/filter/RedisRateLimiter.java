package kr.co.legalai.common.filter;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

/** 다중 인스턴스에서 공유하는 순간 요청 제한. Redis 장애 시 호출자가 로컬 보호로 전환한다. */
@Component
public class RedisRateLimiter {
    private static final DefaultRedisScript<Long> SCRIPT = new DefaultRedisScript<>(
            "local count = redis.call('INCR', KEYS[1]) " +
            "if count == 1 then redis.call('EXPIRE', KEYS[1], 70) end " +
            "if count <= tonumber(ARGV[1]) then return 1 else return 0 end", Long.class);

    private final StringRedisTemplate redis;

    public RedisRateLimiter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** true/false는 판정 결과, null은 Redis를 사용할 수 없어 로컬 보호가 필요함을 뜻한다. */
    public Boolean allow(String requestKey, int limit) {
        try {
            String bucket = "better-call-ai:rate:" + Instant.now().getEpochSecond() / 60;
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(requestKey.getBytes(StandardCharsets.UTF_8)));
            Long result = redis.execute(SCRIPT, List.of(bucket + ":" + digest), Integer.toString(limit));
            return result == null ? null : result == 1L;
        } catch (Exception exception) {
            return null;
        }
    }
}
