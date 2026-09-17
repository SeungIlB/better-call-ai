package kr.co.legalai.legaldata.service;

import kr.co.legalai.legaldata.dto.request.CaseEvidenceSearchRequest;
import kr.co.legalai.legaldata.dto.response.CaseEvidenceSearchResponse;
import java.util.UUID;

public interface CaseEvidenceSearchService {
    CaseEvidenceSearchResponse search(UUID caseId, UUID fileId, CaseEvidenceSearchRequest request);
}
