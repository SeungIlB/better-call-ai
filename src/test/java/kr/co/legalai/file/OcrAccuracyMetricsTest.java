package kr.co.legalai.file;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class OcrAccuracyMetricsTest {
    @Test void whitespaceAndPageMarkersDoNotHideDigitErrors() {
        var exact = OcrAccuracyMetrics.score("보증금: 12,345원", "[페이지 1]\n보증금:12,345원", List.of("보증금: 12,345원"));
        assertTrue(exact.passed());
        var wrong = OcrAccuracyMetrics.score("보증금: 12,345원", "보증금: 12,845원", List.of("보증금: 12,345원"));
        assertEquals(1, wrong.edits());
        assertEquals(0, wrong.criticalMatched());
        assertFalse(wrong.passed());
    }

    @Test void omissionsAdditionsAndReorderedLabelsFail() {
        assertEquals(2, OcrAccuracyMetrics.score("가나다라", "가라", List.of()).edits());
        assertEquals(2, OcrAccuracyMetrics.score("가나다라", "가나다라마바", List.of()).edits());
        var swapped = OcrAccuracyMetrics.score("임대인: 가람\n임차인: 나래", "임대인: 나래\n임차인: 가람",
                List.of("임대인: 가람", "임차인: 나래"));
        assertEquals(0, swapped.criticalMatched());
        assertFalse(swapped.passed());
    }

    @Test void oneCriticalErrorFailsEvenWhenOverallCerIsLow() {
        String expected = "가".repeat(1000) + "총액: 9원";
        var score = OcrAccuracyMetrics.score(expected, "가".repeat(1000) + "총액: 8원", List.of("총액: 9원"));
        assertTrue(score.cer() < 0.01);
        assertFalse(score.passed());
        assertThrows(IllegalArgumentException.class, () -> OcrAccuracyMetrics.score(" ", "내용", List.of()));
    }

    @Test void transliterationAndSimilarLookingCharactersRemainErrors() {
        var score = OcrAccuracyMetrics.score("甲 生 AB-01", "갑 생 AB-O1", List.of("甲", "生", "AB-01"));
        assertEquals(3, score.edits());
        assertEquals(0, score.criticalMatched());
        assertFalse(score.passed());
    }
}
