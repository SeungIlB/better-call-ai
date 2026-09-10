package kr.co.legalai.casework.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Pattern;

public record CreateCaseRequest(
        @NotBlank @Size(max = 200) String title,
        @Size(max = 40) String userPartyRole,
        @Size(max = 2_000) String userGoal,
        @Size(max = 50_000) String originalStatement,
        @Pattern(regexp = "housing_lease|vehicle_accident|assault", message = "지원하지 않는 분쟁 분야입니다.") String disputeDomain
) {
    public CreateCaseRequest(String title, String userPartyRole, String userGoal, String originalStatement) {
        this(title, userPartyRole, userGoal, originalStatement, "housing_lease");
    }

    public String normalizedDomain() {
        return disputeDomain == null || disputeDomain.isBlank() ? "housing_lease" : disputeDomain.trim();
    }
}
