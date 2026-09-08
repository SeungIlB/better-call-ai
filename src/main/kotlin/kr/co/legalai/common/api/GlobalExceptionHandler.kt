package kr.co.legalai.common.api

import jakarta.servlet.http.HttpServletRequest
import org.slf4j.MDC
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.AccessDeniedException
import org.springframework.validation.BindException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.server.ResponseStatusException

@RestControllerAdvice
class GlobalExceptionHandler {
    @ExceptionHandler(AccessDeniedException::class)
    fun forbidden(): ResponseEntity<ApiError> =
        response(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "접근 권한이 없습니다.")

    @ExceptionHandler(MethodArgumentNotValidException::class, BindException::class)
    fun validation(): ResponseEntity<ApiError> =
        response(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "요청 값을 확인해 주세요.")

    @ExceptionHandler(ResponseStatusException::class)
    fun status(error: ResponseStatusException): ResponseEntity<ApiError> =
        response(HttpStatus.valueOf(error.statusCode.value()), "REQUEST_REJECTED", error.reason ?: "요청을 처리할 수 없습니다.")

    @ExceptionHandler(Exception::class)
    fun unexpected(error: Exception, request: HttpServletRequest): ResponseEntity<ApiError> =
        response(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "일시적인 오류가 발생했습니다.")

    private fun response(status: HttpStatus, code: String, message: String) =
        ResponseEntity.status(status).body(ApiError(code, message, MDC.get("traceId")))
}
