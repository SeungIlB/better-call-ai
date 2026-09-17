package kr.co.legalai.legaldata.entity;

import lombok.Builder;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Builder
public record CollectedLaw(String externalId, String title, String serial, LocalDate effectiveFrom,
                           LawKind kind, String sourceUrl, String rawJson, String contentHash,
                           List<Part> parts) {
    public CollectedLaw { parts = List.copyOf(parts); }
    public String versionLabel() { return serial + ":" + effectiveFrom; }

    @Builder
    public record Part(String heading, String type, String content, Map<String, Object> metadata) {
        public Part { metadata = Map.copyOf(metadata); }
    }
}
