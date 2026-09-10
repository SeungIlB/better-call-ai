package kr.co.legalai.file.dto.response;

import lombok.Builder;
import java.time.Instant;
import java.util.UUID;

@Builder
public record ConfirmedEvidenceResponse(UUID fileId, UUID revisionId, String correctedText, String visionJson, Instant confirmedAt) {}
