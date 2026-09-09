package kr.co.legalai.common.exception;

public class CaseNotFoundException extends BusinessException {
    public CaseNotFoundException() {
        super(ErrorCode.CASE_NOT_FOUND);
    }
}
