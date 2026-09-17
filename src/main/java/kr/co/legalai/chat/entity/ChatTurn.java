package kr.co.legalai.chat.entity;

import kr.co.legalai.chat.dto.response.ChatTurnResponse;
import lombok.Builder;

import java.time.Instant;
import java.util.UUID;

@Builder
public record ChatTurn(UUID id, long turnNo, String question, String answer, String status,
                       int attempt, Instant createdAt, Instant answeredAt) {
    public ChatTurnResponse response(boolean latest) {
        return ChatTurnResponse.builder().id(id).question(question).answer(answer).attempt(attempt)
                .retryAllowed(latest && status.equals("FAILED"))
                .createdAt(createdAt).answeredAt(answeredAt).build();
    }
}
