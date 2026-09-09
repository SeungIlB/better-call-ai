package kr.co.legalai.common.response;

import org.slf4j.MDC;

/**
 * 모든 성공 응답에 사용하는 공통 봉투.
 */
public record ApiResponse<T>(
        boolean success,
        T data,
        String traceId
) {
    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(true, data, MDC.get("traceId"));
    }
}
