package kr.co.legalai.legaldata.dto.response;

import java.time.LocalDate;

public record LegalSearchItemResponse(
        String externalId,
        String title,
        String caseNumber,
        String publisher,
        LocalDate publishedOrDecisionDate,
        LocalDate effectiveDate,
        String sourceUrl
) {
}
