package kr.co.legalai.common.exception;

public class CaseVersionConflictException extends BusinessException {
    public CaseVersionConflictException() {
        super(ErrorCode.CASE_VERSION_CONFLICT);
    }
}
