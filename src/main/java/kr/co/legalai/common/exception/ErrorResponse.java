package kr.co.legalai.common.exception;

import java.time.Instant;

public record ErrorResponse(
        boolean success,
        String code,
        String message,
        String traceId,
        Instant occurredAt
) {
    public static ErrorResponse of(ErrorCode errorCode, String traceId) {
        return new ErrorResponse(
                false,
                errorCode.code(),
                errorCode.message(),
                traceId,
                Instant.now()
        );
    }
}
