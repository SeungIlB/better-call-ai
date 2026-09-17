package kr.co.legalai.legaldata.service;

import kr.co.legalai.legaldata.dto.request.CaseEvidenceSearchRequest;
import kr.co.legalai.legaldata.dto.response.LegalDraftResponse;
import java.util.UUID;
import java.util.List;

public interface LegalDraftService {
    LegalDraftResponse generate(UUID caseId, UUID fileId, CaseEvidenceSearchRequest request);
    default LegalDraftResponse generate(UUID caseId, List<UUID> fileIds, CaseEvidenceSearchRequest request) {
        if (fileIds == null || fileIds.size() != 1) throw new UnsupportedOperationException("다중 증거 분석을 지원하지 않습니다.");
        return generate(caseId, fileIds.get(0), request);
    }
}
