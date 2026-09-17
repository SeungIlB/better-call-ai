package kr.co.legalai.chat.dto.response;

import lombok.Builder;

import java.time.Instant;
import java.util.UUID;

/** 내부 실행 상태와 공급자 오류는 노출하지 않는다. answer가 null이면 질문만 렌더링한다. */
@Builder
public record ChatTurnResponse(UUID id, String question, String answer, int attempt,
                               boolean retryAllowed, Instant createdAt, Instant answeredAt) {
}
