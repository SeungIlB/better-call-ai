package kr.co.legalai.legaldata.service.impl;
import java.util.List;
/** 소비자 질문의 검색 표현을 보완한다. */
public final class ConsumerSearchTerms {
    private static final List<String> TERMS = List.of("소비자기본법", "전자상거래", "환불", "청약철회", "약관", "하자");
    private ConsumerSearchTerms() { }
    public static String expand(String query) { return (query == null ? "" : query.strip()) + " " + String.join(" ", TERMS); }
}
