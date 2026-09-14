package kr.co.legalai.legaldata.service.impl;
import java.util.List;
public final class DefamationSearchTerms {
    private static final List<String> TERMS = List.of("명예훼손", "언론중재", "정정보도", "게시물", "피해구제");
    private DefamationSearchTerms() { }
    public static String expand(String query) { return (query == null ? "" : query.strip()) + " " + String.join(" ", TERMS); }
}
