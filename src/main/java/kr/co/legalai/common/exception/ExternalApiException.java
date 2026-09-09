package kr.co.legalai.common.exception;

public class ExternalApiException extends BusinessException {
    public ExternalApiException(String message) {
        super(ErrorCode.EXTERNAL_API_ERROR);
    }

    public ExternalApiException(String message, Throwable cause) {
        super(ErrorCode.EXTERNAL_API_ERROR, cause);
    }
}
