package kr.co.legalai.chat.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Builder;

@Builder
public record SendChatRequest(@NotBlank @Size(max = 4000) String content) {
}
