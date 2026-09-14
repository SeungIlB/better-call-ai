package kr.co.legalai.legaldata.service.impl;
import java.util.List;
public final class PersonalInjurySearchTerms {
    private static final List<String> TERMS = List.of("산업재해", "업무상 재해", "요양", "장해", "치료");
    private PersonalInjurySearchTerms() { }
    public static String expand(String query) { return (query == null ? "" : query.strip()) + " " + String.join(" ", TERMS); }
}
