package kr.co.legalai.file.dto.response;

import lombok.Builder;
import java.util.UUID;

@Builder
public record OcrResponse(UUID fileId, UUID extractionId, String status, String rawText,
        OcrRevisionResponse latestRevision, OcrRevisionResponse confirmedRevision) {}
