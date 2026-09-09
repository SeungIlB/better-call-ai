package kr.co.legalai.chat.dto.request;

import jakarta.validation.constraints.Min;
import lombok.Builder;

@Builder
public record RetryChatRequest(@Min(1) int expectedAttempt) {
}
