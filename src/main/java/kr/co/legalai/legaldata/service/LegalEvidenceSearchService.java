package kr.co.legalai.legaldata.service;

import kr.co.legalai.common.response.PageResponse;
import kr.co.legalai.legaldata.dto.response.LegalEvidenceResponse;

public interface LegalEvidenceSearchService {
    PageResponse<LegalEvidenceResponse> search(String query);
}
