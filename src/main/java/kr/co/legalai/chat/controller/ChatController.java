package kr.co.legalai.chat.controller;

import jakarta.validation.Valid;
import kr.co.legalai.chat.dto.request.RetryChatRequest;
import kr.co.legalai.chat.dto.request.SendChatRequest;
import kr.co.legalai.chat.dto.response.ChatTurnResponse;
import kr.co.legalai.chat.service.ChatService;
import kr.co.legalai.common.response.ApiResponse;
import kr.co.legalai.common.response.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/cases/{caseId}/chat/turns")
public class ChatController {
    private final ChatService service;

    @PostMapping
    public ApiResponse<ChatTurnResponse> send(@PathVariable UUID caseId,
            @RequestHeader("Idempotency-Key") UUID key, @Valid @RequestBody SendChatRequest request) {
        return ApiResponse.success(service.send(caseId, key, request.content()));
    }

    @PostMapping("/{id}/retry")
    public ApiResponse<ChatTurnResponse> retry(@PathVariable UUID caseId, @PathVariable UUID id,
            @Valid @RequestBody RetryChatRequest request) {
        return ApiResponse.success(service.retry(caseId, id, request.expectedAttempt()));
    }

    @GetMapping
    public ApiResponse<PageResponse<ChatTurnResponse>> list(@PathVariable UUID caseId,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.success(service.list(caseId, page, pageSize));
    }
}
