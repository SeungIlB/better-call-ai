package kr.co.legalai.legaldata.service.impl;

import java.util.LinkedHashSet;
import java.util.List;

/** 검색 후보 용어만 추가한다. 사실 확정이나 적용 조문 선택은 하지 않는다. */
public final class HousingSearchTerms {
    private HousingSearchTerms() { }

    public static String expand(String query) {
        String original = query.strip();
        if (!contains(original, "집", "주택", "임대", "임차", "세입자", "전세", "월세", "방")) return original;
        var terms = new LinkedHashSet<String>();
        boolean repair = contains(original, "수리", "수선", "고장", "누수", "곰팡이", "보일러", "하자", "물이 새", "물이 샙");
        if (repair) {
            terms.addAll(List.of("임대인", "의무", "사용", "수익", "필요한", "상태", "유지", "수선", "보존"));
            if (contains(original, "돈", "비용", "수리비", "냈", "지출", "청구")) {
                terms.addAll(List.of("임차인", "필요비", "상환청구권", "상환"));
            }
        }
        if (contains(original, "못 쓰", "쓸 수 없", "사용할 수 없", "못 사용")) {
            terms.addAll(List.of("일부멸실", "차임", "감액", "해지"));
        }
        if (original.contains("보증금") && contains(original, "돌려", "못 받", "반환", "안 줘", "안 주")) {
            terms.addAll(List.of("임대차", "종료", "보증금", "반환", "임차권등기명령"));
        }
        if (contains(original, "묵시", "자동 연장")) {
            terms.addAll(List.of("묵시적", "갱신", "해지", "통지"));
        }
        return terms.isEmpty() ? original : original + "\n검색 관련 용어: " + String.join(" ", terms);
    }

    private static boolean contains(String text, String... words) {
        for (String word : words) if (text.contains(word)) return true;
        return false;
    }
}
