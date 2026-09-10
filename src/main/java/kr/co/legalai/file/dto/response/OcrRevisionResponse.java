package kr.co.legalai.file.dto.response;

import lombok.Builder;
import java.time.Instant;
import java.util.UUID;

@Builder
public record OcrRevisionResponse(UUID id, UUID extractionId, int revision,
        String correctedText, boolean current, Instant confirmedAt, Instant createdAt) {}
