package kr.co.legalai.legaldata.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CaseEvidenceSearchRequest(@NotBlank @Size(max = 300) String query,
        @Min(1) int expectedCaseVersion, @Min(0) Integer excerptStart) { }
