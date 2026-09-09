package kr.co.legalai.common.exception;

import org.springframework.http.HttpStatus;

public enum ErrorCode {
    VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "COMMON_001", "요청 값을 확인해 주세요."),
    AUTHENTICATION_REQUIRED(HttpStatus.UNAUTHORIZED, "AUTH_001", "인증이 필요합니다."),
    ACCESS_DENIED(HttpStatus.FORBIDDEN, "AUTH_002", "접근 권한이 없습니다."),
    EMAIL_ALREADY_REGISTERED(HttpStatus.CONFLICT, "AUTH_003", "이미 가입된 이메일입니다."),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "AUTH_004", "이메일 또는 비밀번호를 확인해 주세요."),
    INVALID_REFRESH_TOKEN(HttpStatus.UNAUTHORIZED, "AUTH_005", "Refresh Token이 유효하지 않습니다."),
    REFRESH_TOKEN_REUSED(HttpStatus.UNAUTHORIZED, "AUTH_006", "Refresh Token 재사용이 감지되었습니다. 다시 로그인해 주세요."),
    ACCOUNT_UNAVAILABLE(HttpStatus.UNAUTHORIZED, "AUTH_007", "사용할 수 없는 계정입니다."),
    LOGIN_RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "AUTH_008", "로그인 시도가 제한되었습니다. 15분 후 다시 시도해 주세요."),
    CASE_NOT_FOUND(HttpStatus.NOT_FOUND, "CASE_001", "사건을 찾을 수 없습니다."),
    CASE_VERSION_CONFLICT(HttpStatus.CONFLICT, "CASE_002", "사건이 다른 요청에 의해 변경되었습니다."),
    FILE_NOT_FOUND(HttpStatus.NOT_FOUND, "FILE_001", "파일을 찾을 수 없습니다."),
    INVALID_FILE(HttpStatus.BAD_REQUEST, "FILE_002", "파일 이름, 형식 또는 내용을 확인해 주세요."),
    FILE_TOO_LARGE(HttpStatus.CONTENT_TOO_LARGE, "FILE_003", "파일 크기 제한을 초과했습니다."),
    FILE_PAGE_LIMIT(HttpStatus.BAD_REQUEST, "FILE_004", "PDF는 1~30페이지까지 업로드할 수 있습니다."),
    FILE_CASE_LIMIT(HttpStatus.CONFLICT, "FILE_005", "사건의 파일 개수 또는 용량 제한을 초과했습니다."),
    FILE_STORAGE_ERROR(HttpStatus.SERVICE_UNAVAILABLE, "FILE_006", "임시 파일 저장소를 사용할 수 없습니다."),
    UNSAFE_FILE(HttpStatus.UNPROCESSABLE_CONTENT, "FILE_007", "안전하지 않은 파일은 업로드할 수 없습니다."),
    UPLOAD_DAILY_LIMIT(HttpStatus.TOO_MANY_REQUESTS, "FILE_009", "현재 플랜의 오늘 업로드 횟수를 모두 사용했습니다."),
    UPLOAD_KEY_CONFLICT(HttpStatus.CONFLICT, "FILE_010", "동일한 요청 키에 다른 파일 또는 사건을 사용할 수 없습니다."),
    UPLOAD_IN_PROGRESS(HttpStatus.CONFLICT, "FILE_011", "같은 요청이 처리 중입니다. 잠시 후 동일한 키로 확인해 주세요."),
    MALWARE_SCAN_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "FILE_008", "파일 보안 검사를 완료할 수 없습니다. 잠시 후 다시 시도해 주세요."),
    EXTERNAL_API_ERROR(HttpStatus.BAD_GATEWAY, "LEGAL_DATA_001", "외부 법률 데이터 서비스 호출에 실패했습니다."),
    INTEGRATION_NOT_CONFIGURED(HttpStatus.SERVICE_UNAVAILABLE, "COMMON_002", "외부 연동 설정이 필요합니다."),
    CHAT_KEY_CONFLICT(HttpStatus.CONFLICT, "CHAT_001", "같은 요청 키에 다른 질문을 사용할 수 없습니다."),
    CHAT_BUSY(HttpStatus.TOO_MANY_REQUESTS, "CHAT_002", "잠시 후 다시 시도해 주세요."),
    CHAT_GENERATION_FAILED(HttpStatus.SERVICE_UNAVAILABLE, "CHAT_003", "답변을 제공하지 못했습니다. 잠시 후 재시도해 주세요."),
    CHAT_NOT_FOUND(HttpStatus.NOT_FOUND, "CHAT_004", "대화를 찾을 수 없습니다."),
    CHAT_SAVE_FAILED(HttpStatus.SERVICE_UNAVAILABLE, "CHAT_005", "답변 저장을 확인하지 못했습니다. 대화를 다시 조회해 주세요."),
    CHAT_RETRY_CONFLICT(HttpStatus.CONFLICT, "CHAT_006", "현재 대화를 다시 조회한 후 마지막 질문을 재시도해 주세요."),
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
