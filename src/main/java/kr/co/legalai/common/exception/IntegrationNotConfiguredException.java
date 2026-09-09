package kr.co.legalai.common.exception;

public class IntegrationNotConfiguredException extends BusinessException {
    public IntegrationNotConfiguredException() {
        super(ErrorCode.INTEGRATION_NOT_CONFIGURED);
    }
}
