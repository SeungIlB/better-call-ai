package kr.co.legalai.common.exception;

public class ExternalApiException extends BusinessException {
    public ExternalApiException() {
        super(ErrorCode.EXTERNAL_API_ERROR);
    }

    public ExternalApiException(Throwable cause) {
        super(ErrorCode.EXTERNAL_API_ERROR, cause);
    }
}
