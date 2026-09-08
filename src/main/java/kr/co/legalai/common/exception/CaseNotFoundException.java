package kr.co.legalai.common.exception;

public class CaseNotFoundException extends RuntimeException {
    public CaseNotFoundException() {
        super("사건을 찾을 수 없습니다.");
    }
}
