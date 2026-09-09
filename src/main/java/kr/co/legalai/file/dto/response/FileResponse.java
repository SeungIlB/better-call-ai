package kr.co.legalai.file.dto.response;

import lombok.Builder;

import java.time.Instant;
import java.util.UUID;

@Builder
public record FileResponse(
        UUID id, UUID caseId, String originalName, String mimeType, long sizeBytes,
        Integer pageCount, String lifecycleStatus, String malwareStatus, String purgeStatus,
        Instant createdAt, Instant storageExpiresAt
) {
}
