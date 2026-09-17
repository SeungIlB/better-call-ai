package kr.co.legalai.legaldata.service.impl;
import java.util.List;
/** 노동·임금 질문의 검색 표현을 보완한다. 법률 결론이나 사실을 추가하지 않는다. */
public final class LaborSearchTerms {
    private static final List<String> TERMS = List.of("근로기준법", "임금", "퇴직금", "해고", "근로계약", "휴업");
    private LaborSearchTerms() { }
    public static String expand(String query) { return (query == null ? "" : query.strip()) + " " + String.join(" ", TERMS); }
}
