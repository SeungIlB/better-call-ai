package kr.co.legalai.legaldata.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SearchLegalEvidenceRequest(@NotBlank @Size(max = 1000) String query) { }
