package kr.co.legalai.common.api;

import java.time.Instant;

public record ApiError(
        String code,
        String message,
        String traceId,
        Instant occurredAt
) {
    public ApiError(String code, String message, String traceId) {
        this(code, message, traceId, Instant.now());
    }
}
