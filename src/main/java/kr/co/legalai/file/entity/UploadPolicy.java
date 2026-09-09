package kr.co.legalai.file.entity;

import lombok.Builder;

@Builder
public record UploadPolicy(long maxBytes, int maxPdfPages, int maxFiles, long maxCaseBytes, int retentionHours) {
    public UploadPolicy {
        if (maxBytes < 1 || maxBytes > 20L * 1024 * 1024 || maxPdfPages < 1 || maxPdfPages > 30
                || maxFiles < 1 || maxCaseBytes < 1 || retentionHours < 1 || retentionHours > 24) {
            throw new IllegalStateException("업로드 정책 범위를 확인해 주세요.");
        }
    }
}
