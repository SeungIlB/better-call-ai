package kr.co.legalai.legaldata.service;

import kr.co.legalai.legaldata.dto.response.LegalDocumentResponse;
import kr.co.legalai.legaldata.dto.response.LegalSearchResponse;
import kr.co.legalai.legaldata.entity.LegalDocumentType;

public interface LegalDataService {
    LegalSearchResponse search(LegalDocumentType type, String query, int page, int pageSize);

    LegalDocumentResponse getDocument(LegalDocumentType type, String externalId);
}
