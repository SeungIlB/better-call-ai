package kr.co.legalai.common.exception;

public class CaseVersionConflictException extends RuntimeException {
    public CaseVersionConflictException() {
        super("사건이 다른 요청에 의해 변경되었습니다.");
    }
}
