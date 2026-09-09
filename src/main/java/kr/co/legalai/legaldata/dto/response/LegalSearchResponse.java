package kr.co.legalai.legaldata.dto.response;

import kr.co.legalai.legaldata.entity.LegalDocumentType;

import java.util.List;

public record LegalSearchResponse(
        LegalDocumentType type,
        String query,
        int page,
        int pageSize,
        int totalCount,
        List<LegalSearchItemResponse> items
) {
}
