package kr.co.legalai.analysis.dto.response;

import kr.co.legalai.analysis.dto.request.AnalysisRequest;
import kr.co.legalai.legaldata.dto.response.LegalDraftResponse;
import lombok.Builder;
import java.time.Instant;
import java.util.UUID;

@Builder
public record AnalysisResponse(UUID id, UUID caseId, int version, String status, boolean stale,
        String staleReason, String errorCode, Instant createdAt, AnalysisRequest request, LegalDraftResponse result) { }
