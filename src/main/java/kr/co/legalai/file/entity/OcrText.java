package kr.co.legalai.file.entity;

import lombok.Builder;

import java.util.List;

@Builder
public record OcrText(String text, String model, String responseId, Integer inputTokens, Integer outputTokens,
        List<String> observations, List<String> unknowns) {}
