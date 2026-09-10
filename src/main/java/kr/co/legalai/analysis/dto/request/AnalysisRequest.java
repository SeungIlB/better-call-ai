package kr.co.legalai.analysis.dto.request;

import jakarta.validation.constraints.*;
import java.util.UUID;
import java.util.List;

public record AnalysisRequest(@NotNull UUID fileId, @NotBlank @Size(max = 300) String query,
        @Min(1) int expectedCaseVersion, @Min(0) Integer excerptStart, List<UUID> fileIds) {
    public AnalysisRequest(UUID fileId, String query, int expectedCaseVersion, Integer excerptStart) {
        this(fileId, query, expectedCaseVersion, excerptStart, null);
    }

    public List<UUID> selectedFileIds() {
        return fileIds == null || fileIds.isEmpty() ? List.of(fileId) : fileIds;
    }
}
