package kr.co.legalai.legaldata.dto.response;

import lombok.Builder;
import java.time.LocalDate;
import java.util.UUID;

@Builder
public record LegalEvidenceResponse(UUID chunkId, UUID documentId, String title, String heading,
        String content, String sourceUrl, String versionLabel, LocalDate effectiveFrom, double rankScore) { }
