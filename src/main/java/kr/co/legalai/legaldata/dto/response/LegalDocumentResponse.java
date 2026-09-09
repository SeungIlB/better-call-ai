package kr.co.legalai.legaldata.dto.response;

import kr.co.legalai.legaldata.entity.LegalDocumentType;

import java.time.LocalDate;

public record LegalDocumentResponse(
        LegalDocumentType type,
        String externalId,
        String title,
        String caseNumber,
        String publisher,
        LocalDate publishedOrDecisionDate,
        LocalDate effectiveDate,
        String sourceUrl,
        String normalizedText
) {
}
