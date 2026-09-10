package kr.co.legalai.file.dto.request;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import java.util.UUID;

@Builder
public record ConfirmOcrRequest(@NotNull UUID revisionId, @AssertTrue boolean sensitiveDataReviewed) {}
