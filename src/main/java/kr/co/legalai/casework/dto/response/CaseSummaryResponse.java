package kr.co.legalai.casework.dto.response;

import java.time.Instant;
import java.util.UUID;

/** 목록에 필요한 정보만 제공하며 사건 진술과 목표 본문은 포함하지 않는다. */
public record CaseSummaryResponse(
        UUID id,
        String title,
        String status,
        String userPartyRole,
        int version,
        Instant createdAt,
        Instant updatedAt
) {
}
