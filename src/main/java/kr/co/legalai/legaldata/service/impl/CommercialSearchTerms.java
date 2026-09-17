package kr.co.legalai.legaldata.service.impl;
import java.util.List;
public final class CommercialSearchTerms {
    private static final List<String> TERMS = List.of("전자금융거래", "하도급", "거래대금", "계약", "납품");
    private CommercialSearchTerms() { }
    public static String expand(String query) { return (query == null ? "" : query.strip()) + " " + String.join(" ", TERMS); }
}
