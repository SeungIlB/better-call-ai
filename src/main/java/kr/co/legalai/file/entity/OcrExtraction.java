package kr.co.legalai.file.entity;

import lombok.Builder;
import java.util.UUID;

@Builder
public record OcrExtraction(UUID id, String status, String rawText) {}
