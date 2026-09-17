package kr.co.legalai.legaldata.service.impl;
import java.util.List;
public final class InheritanceSearchTerms {
    private static final List<String> TERMS = List.of("상속세", "상속", "상속인", "유산", "증여");
    private InheritanceSearchTerms() { }
    public static String expand(String query) { return (query == null ? "" : query.strip()) + " " + String.join(" ", TERMS); }
}
