package kr.co.legalai.auth.dto.response;

import java.util.UUID;

public record UserResponse(
        UUID id,
        String email,
        String displayName
) {
}
