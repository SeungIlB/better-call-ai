package kr.co.legalai.casework.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record UpdateCaseRequest(
        @NotBlank @Size(max = 50_000) String originalStatement,
        @Positive int expectedVersion
) {
}
