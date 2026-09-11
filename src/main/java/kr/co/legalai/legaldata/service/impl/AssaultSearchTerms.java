package kr.co.legalai.legaldata.service.impl;

import java.util.List;

/** 폭행 질문에만 검색용 표현을 보완한다. 유죄·책임 결론을 추가하지 않는다. */
public final class AssaultSearchTerms {
    private static final List<String> TERMS = List.of("폭행", "상해", "형법", "고소", "진단서");
    private AssaultSearchTerms() { }
    public static String expand(String query) {
        String value = query == null ? "" : query.strip();
        return value.isBlank() ? value : value + "\n검색 보완어: " + String.join(" ", TERMS);
    }
}
