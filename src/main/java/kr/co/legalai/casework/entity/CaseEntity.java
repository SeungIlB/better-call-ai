package kr.co.legalai.casework.entity;

import java.time.Instant;
import java.util.UUID;

public record CaseEntity(
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
