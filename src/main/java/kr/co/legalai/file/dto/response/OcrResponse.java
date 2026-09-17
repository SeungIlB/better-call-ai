package kr.co.legalai.file.dto.response;

import lombok.Builder;
import java.util.UUID;

@Builder
public record OcrResponse(UUID fileId, UUID extractionId, String status, String rawText,
        String visionJson, OcrRevisionResponse latestRevision, OcrRevisionResponse confirmedRevision,
        java.util.List<PiiFindingResponse> piiFindings) {}
