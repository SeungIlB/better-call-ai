package kr.co.legalai.legaldata.service;

import kr.co.legalai.legaldata.dto.request.CaseEvidenceSearchRequest;
import kr.co.legalai.legaldata.dto.response.LegalDraftResponse;
import java.util.UUID;

public interface LegalDraftService {
    LegalDraftResponse generate(UUID caseId, UUID fileId, CaseEvidenceSearchRequest request);
}
