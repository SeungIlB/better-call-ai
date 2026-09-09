package kr.co.legalai.common.exception;

import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(CaseNotFoundException.class)
    ResponseEntity<ErrorResponse> notFound(CaseNotFoundException exception) {
        return response(HttpStatus.NOT_FOUND, "CASE_NOT_FOUND", exception.getMessage());
    }

    @ExceptionHandler(CaseVersionConflictException.class)
    ResponseEntity<ErrorResponse> conflict(CaseVersionConflictException exception) {
        return response(HttpStatus.CONFLICT, "CASE_VERSION_CONFLICT", exception.getMessage());
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ErrorResponse> forbidden() {
        return response(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "접근 권한이 없습니다.");
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, BindException.class, IllegalArgumentException.class})
    ResponseEntity<ErrorResponse> validation() {
        return response(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "요청 값을 확인해 주세요.");
    }

    @ExceptionHandler(IntegrationNotConfiguredException.class)
    ResponseEntity<ErrorResponse> integrationNotConfigured(IntegrationNotConfiguredException exception) {
        return response(HttpStatus.SERVICE_UNAVAILABLE, "INTEGRATION_NOT_CONFIGURED", exception.getMessage());
    }

    @ExceptionHandler(ExternalApiException.class)
    ResponseEntity<ErrorResponse> externalApi(ExternalApiException exception) {
        return response(HttpStatus.BAD_GATEWAY, "EXTERNAL_API_ERROR", exception.getMessage());
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorResponse> unexpected() {
        return response(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "일시적인 오류가 발생했습니다.");
    }

    private ResponseEntity<ErrorResponse> response(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(new ErrorResponse(code, message, MDC.get("traceId")));
    }
}
