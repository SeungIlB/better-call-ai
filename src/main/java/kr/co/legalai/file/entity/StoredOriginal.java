package kr.co.legalai.file.entity;

import lombok.Builder;

import java.nio.file.Path;
import java.util.UUID;

@Builder
public record StoredOriginal(UUID id, Path path, long sizeBytes, String sha256) {
}
