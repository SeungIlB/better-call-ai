package kr.co.legalai.file.entity;

import lombok.Builder;

@Builder
public record UploadFingerprint(String sha256, long sizeBytes, String requestHash) {
}
