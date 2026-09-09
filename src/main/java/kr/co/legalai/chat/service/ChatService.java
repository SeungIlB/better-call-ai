package kr.co.legalai.chat.service;

import kr.co.legalai.chat.dto.response.ChatTurnResponse;
import kr.co.legalai.common.response.PageResponse;

import java.util.UUID;

public interface ChatService {
    ChatTurnResponse send(UUID caseId, UUID key, String content);
    ChatTurnResponse retry(UUID caseId, UUID id, int expectedAttempt);
    PageResponse<ChatTurnResponse> list(UUID caseId, int page, int pageSize);
}
