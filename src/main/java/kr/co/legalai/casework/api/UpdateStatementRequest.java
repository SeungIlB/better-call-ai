package kr.co.legalai.casework.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record UpdateStatementRequest(
        @NotBlank @Size(max = 50_000) String statement,
        @Positive int expectedVersion
) {
}
