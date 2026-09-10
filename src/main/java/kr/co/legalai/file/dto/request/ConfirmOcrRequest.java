package kr.co.legalai.file.dto.request;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import java.util.UUID;
import java.util.List;

@Builder
public record ConfirmOcrRequest(@NotNull UUID revisionId, @AssertTrue boolean sensitiveDataReviewed,
        List<@NotNull @jakarta.validation.constraints.Size(max = 300) String> observations,
        List<@NotNull @jakarta.validation.constraints.Size(max = 300) String> unknowns) {}
