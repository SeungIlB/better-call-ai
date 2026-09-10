package kr.co.legalai.file.dto.request;

import jakarta.validation.constraints.AssertTrue;
import lombok.Builder;

@Builder
public record StartOcrRequest(@AssertTrue boolean externalOcrAccepted) {}
