package kr.co.legalai.legaldata.service.impl;
import java.util.List;
public final class FamilySearchTerms {
    private static final List<String> TERMS = List.of("가족관계", "가사소송", "이혼", "양육", "친권");
    private FamilySearchTerms() { }
    public static String expand(String query) { return (query == null ? "" : query.strip()) + " " + String.join(" ", TERMS); }
}
