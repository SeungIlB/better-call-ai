package kr.co.legalai.chat.entity;

import lombok.Builder;

@Builder
public record GeneratedAnswer(String text, String model, String responseId,
                              Integer inputTokens, Integer outputTokens) {
}
