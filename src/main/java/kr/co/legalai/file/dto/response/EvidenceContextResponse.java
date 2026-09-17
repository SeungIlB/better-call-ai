package kr.co.legalai.file.dto.response;

import kr.co.legalai.common.response.PageResponse;
import lombok.Builder;
import java.util.UUID;

@Builder
public record EvidenceContextResponse(UUID caseId, int caseVersion, PageResponse<ConfirmedEvidenceResponse> evidence) {}
