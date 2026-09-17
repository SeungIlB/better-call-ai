package kr.co.legalai.legaldata.dto.response;

import kr.co.legalai.legaldata.entity.LawKind;

import java.time.LocalDate;

public record LegalSearchItemResponse(
        String externalId,
        String title,
        String caseNumber,
        String publisher,
        LocalDate publishedOrDecisionDate,
        LocalDate effectiveDate,
        String sourceUrl,
        LawKind lawKind
) {
}
