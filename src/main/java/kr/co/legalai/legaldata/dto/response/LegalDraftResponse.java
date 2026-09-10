package kr.co.legalai.legaldata.dto.response;

import lombok.Builder;
import java.util.List;
import java.util.UUID;

@Builder
public record LegalDraftResponse(UUID caseId, int caseVersion, EvidenceExcerptResponse evidence, String status,
        String summary, List<GroundedFindingResponse> findings, List<String> questions, String notice) { }
