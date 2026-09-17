package kr.co.legalai.casework.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Pattern;

public record UpdateCaseRequest(
        @NotBlank @Size(max = 50_000) String originalStatement,
        @Positive int expectedVersion,
        @Size(max = 40) String userPartyRole,
        @Size(max = 2_000) String userGoal,
        @Pattern(regexp = "housing_lease|vehicle_accident|assault|labor|consumer|commercial|family|inheritance|defamation|personal_injury", message = "지원하지 않는 분쟁 분야입니다.") String disputeDomain
) {
    public UpdateCaseRequest(String originalStatement, int expectedVersion) {
        this(originalStatement, expectedVersion, null, null, null);
    }

    public String normalizedDomain() {
        return disputeDomain == null || disputeDomain.isBlank() ? null : disputeDomain.trim();
    }
}
