package kr.co.legalai.file.entity;

import lombok.Builder;
import java.util.UUID;

@Builder
public record OcrFile(UUID id, String mimeType, long sizeBytes, String sha256,
        UUID extractionId, UUID revisionId, boolean available) {}
