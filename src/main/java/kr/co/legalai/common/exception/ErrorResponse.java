package kr.co.legalai.common.exception;

import java.time.Instant;

public record ErrorResponse(
        String code,
        String message,
        String traceId,
        Instant occurredAt
) {
    public ErrorResponse(String code, String message, String traceId) {
        this(code, message, traceId, Instant.now());
    }
}
