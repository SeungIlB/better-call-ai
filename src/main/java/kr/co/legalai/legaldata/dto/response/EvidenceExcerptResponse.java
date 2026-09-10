package kr.co.legalai.legaldata.dto.response;

import lombok.Builder;
import java.util.UUID;

/** start는 포함, end는 미포함인 UTF-16 위치. 원본 파일이 아니라 확정 수정본 기준이다. */
@Builder
public record EvidenceExcerptResponse(UUID fileId, UUID revisionId, String text,
        int start, int end, int totalLength, boolean partial) { }
