package kr.co.legalai.file;

import kr.co.legalai.file.service.impl.PiiDetector;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PiiDetectorTest {
    @Test
    void detectsCandidateTypesWithoutReturningOriginalText() {
        String text = "연락처 010-1234-5678, 이메일 test@example.com, 주민번호 900101-1234567, 사업자 123-45-67890";
        var findings = PiiDetector.detect(text);
        assertEquals(4, findings.size());
        assertEquals(java.util.List.of("phone", "email", "resident_registration", "business_registration"),
                findings.stream().map(item -> item.type()).toList());
        assertTrue(findings.stream().allMatch(item -> item.start() >= 0 && item.end() > item.start()));
    }

    @Test
    void doesNotFlagSimilarOrdinaryNumbers() {
        assertTrue(PiiDetector.detect("주문번호 021번, 금액 28,800원, 날짜 2026-09-11").isEmpty());
    }
}
