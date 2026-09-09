package kr.co.legalai.common.exception;

import org.springframework.http.HttpStatus;

public enum ErrorCode {
    VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "COMMON_001", "요청 값을 확인해 주세요."),
    AUTHENTICATION_REQUIRED(HttpStatus.UNAUTHORIZED, "AUTH_001", "인증이 필요합니다."),
    ACCESS_DENIED(HttpStatus.FORBIDDEN, "AUTH_002", "접근 권한이 없습니다."),
    CASE_NOT_FOUND(HttpStatus.NOT_FOUND, "CASE_001", "사건을 찾을 수 없습니다."),
    CASE_VERSION_CONFLICT(HttpStatus.CONFLICT, "CASE_002", "사건이 다른 요청에 의해 변경되었습니다."),
    EXTERNAL_API_ERROR(HttpStatus.BAD_GATEWAY, "LEGAL_DATA_001", "외부 법률 데이터 서비스 호출에 실패했습니다."),
    INTEGRATION_NOT_CONFIGURED(HttpStatus.SERVICE_UNAVAILABLE, "COMMON_002", "외부 연동 설정이 필요합니다."),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "COMMON_999", "일시적인 오류가 발생했습니다.");

    private final HttpStatus status;
    private final String code;
    private final String message;

    ErrorCode(HttpStatus status, String code, String message) {
        this.status = status;
        this.code = code;
        this.message = message;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public String message() {
        return message;
    }
}
