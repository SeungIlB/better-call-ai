package kr.co.legalai.casework.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateCaseRequest(
        @NotBlank @Size(max = 200) String title,
        @Size(max = 40) String userPartyRole,
        @Size(max = 2_000) String userGoal,
        @Size(max = 50_000) String originalStatement
) {
}
