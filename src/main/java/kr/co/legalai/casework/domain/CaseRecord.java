package kr.co.legalai.casework.domain;

import java.time.Instant;
import java.util.UUID;

public record CaseRecord(
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
