package kr.co.legalai.file.entity;

import lombok.Builder;

@Builder
public record OcrText(String text, String model, String responseId, Integer inputTokens, Integer outputTokens) {}
