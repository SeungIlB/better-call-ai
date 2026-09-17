package kr.co.legalai.file.entity;

import lombok.Builder;
import java.util.UUID;

@Builder
public record UploadRequest(UUID fileId, String fingerprint, String status, String errorCode) {
}
