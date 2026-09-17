package kr.co.legalai.legaldata.service.impl;

import java.util.List;

/** 차량 사고 질문에만 검색용 표현을 보완한다. 법률 결론이나 사실을 추가하지 않는다. */
public final class VehicleSearchTerms {
    private static final List<String> TERMS = List.of("교통사고", "자동차손해배상", "도로교통법", "손해배상", "보험");

    private VehicleSearchTerms() { }

    public static String expand(String query) {
        String value = query == null ? "" : query.strip();
        if (value.isBlank()) return value;
        return value + "\n검색 보완어: " + String.join(" ", TERMS);
    }
}
