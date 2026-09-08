package kr.co.legalai.casework.dto.response;

import java.time.Instant;
import java.util.UUID;

public record CaseResponse(
        UUID id,
        String title,
        String status,
        String userPartyRole,
        String userGoal,
        String originalStatement,
        int version,
        Instant createdAt,
        Instant updatedAt
) {
}
