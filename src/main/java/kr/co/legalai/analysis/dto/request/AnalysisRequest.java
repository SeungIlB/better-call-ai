package kr.co.legalai.analysis.dto.request;

import jakarta.validation.constraints.*;
import java.util.UUID;

public record AnalysisRequest(@NotNull UUID fileId, @NotBlank @Size(max = 300) String query,
        @Min(1) int expectedCaseVersion, @Min(0) Integer excerptStart) { }
