package kr.co.legalai.legaldata.dto.response;

import kr.co.legalai.common.response.PageResponse;
import lombok.Builder;
import java.util.UUID;

@Builder
public record CaseEvidenceSearchResponse(UUID caseId, int caseVersion, String disputeDomain,
        EvidenceExcerptResponse evidence, PageResponse<LegalEvidenceResponse> results) { }
