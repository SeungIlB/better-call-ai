package kr.co.legalai.analysis.service;

import kr.co.legalai.analysis.dto.request.AnalysisRequest;
import kr.co.legalai.analysis.dto.response.AnalysisResponse;
import kr.co.legalai.common.response.PageResponse;
import java.util.UUID;

public interface AnalysisService {
    AnalysisResponse create(UUID caseId, UUID key, AnalysisRequest request);
    AnalysisResponse get(UUID caseId, UUID id);
    PageResponse<AnalysisResponse> list(UUID caseId, int page, int pageSize);
}
