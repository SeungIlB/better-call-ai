package kr.co.legalai.file.dto.request;

import jakarta.validation.constraints.*;
import lombok.Builder;
import java.util.UUID;

@Builder
public record SaveOcrRevisionRequest(
        @NotNull UUID extractionId,
        @NotNull @Min(0) @Max(10000) Integer expectedRevision,
        @NotBlank @Size(max = 100000) @Pattern(regexp = "[^\\x00]*") String correctedText
) {}
