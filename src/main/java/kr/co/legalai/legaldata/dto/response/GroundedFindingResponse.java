package kr.co.legalai.legaldata.dto.response;

import lombok.Builder;

@Builder
public record GroundedFindingResponse(String explanation, String quote, LegalEvidenceResponse source) { }
