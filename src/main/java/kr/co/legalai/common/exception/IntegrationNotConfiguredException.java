package kr.co.legalai.common.exception;

public class IntegrationNotConfiguredException extends BusinessException {
    public IntegrationNotConfiguredException(String message) {
        super(ErrorCode.INTEGRATION_NOT_CONFIGURED);
    }
}
