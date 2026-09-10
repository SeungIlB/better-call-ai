package kr.co.legalai.legaldata;

import kr.co.legalai.legaldata.service.impl.HousingSearchTerms;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class HousingSearchTermsTest {
    @Test void conjugatedLeakExpressionAddsRepairTerms() {
        assertTrue(HousingSearchTerms.expand("월셋집 천장에서 물이 샙니다").contains("수선"));
    }

    @Test void repairExpenseKeepsOriginalAndAddsTermsWithoutArticleNumbers() {
        String original = "집 수리비를 제가 냈는데 받을 수 있나요?";
        String expanded = HousingSearchTerms.expand(original);
        assertTrue(expanded.startsWith(original + "\n"));
        assertTrue(expanded.contains("필요비"));
        assertFalse(expanded.contains("제626조"));
        assertFalse(expanded.contains("제623조"));
    }

    @Test void unrelatedQuestionsAndRepairWithoutExpenseDoNotGainExpenseClaims() {
        assertEquals("자동차 수리비 환불", HousingSearchTerms.expand("자동차 수리비 환불"));
        assertEquals("주식 손실 보상", HousingSearchTerms.expand("주식 손실 보상"));
        assertFalse(HousingSearchTerms.expand("전셋집 보일러가 고장났어요").contains("필요비"));
        assertEquals("집 주소를 확인하고 싶어요", HousingSearchTerms.expand("  집 주소를 확인하고 싶어요  "));
    }

    @Test void maximumInputRemainsWithinEmbeddingByteBudget() {
        String query = "집 수리비 보증금 반환 자동 연장 방을 못 쓰";
        query += "가".repeat(1000 - query.length());
        assertTrue(HousingSearchTerms.expand(query).getBytes(StandardCharsets.UTF_8).length <= 6000);
    }
}
