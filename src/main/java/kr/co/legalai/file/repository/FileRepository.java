package kr.co.legalai.file.repository;

import lombok.RequiredArgsConstructor;

import kr.co.legalai.file.dto.response.FileResponse;
import kr.co.legalai.file.entity.StoredOriginal;
import kr.co.legalai.file.entity.UploadPolicy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class FileRepository {
    private final JdbcTemplate jdbc;


    public UploadPolicy policy() {
        var values = jdbc.queryForList("""
                SELECT setting_key, value_json::text AS value FROM ops.runtime_settings
                WHERE setting_key IN ('upload.max_file_bytes','upload.max_pdf_pages','upload.max_files_per_case',
                                      'upload.max_case_bytes','original.max_retention_hours')
                """);
        var limits = new java.util.HashMap<String, Long>();
        values.forEach(row -> limits.put((String) row.get("setting_key"), Long.valueOf((String) row.get("value"))));
        return UploadPolicy.builder()
                .maxBytes(limits.get("upload.max_file_bytes"))
                .maxPdfPages(limits.get("upload.max_pdf_pages").intValue())
                .maxFiles(limits.get("upload.max_files_per_case").intValue())
                .maxCaseBytes(limits.get("upload.max_case_bytes"))
                .retentionHours(limits.get("original.max_retention_hours").intValue())
                .build();
    }

    public boolean isAllowedMime(String mime) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT value_json @> jsonb_build_array(?::text) FROM ops.runtime_settings
                WHERE setting_key = 'upload.allowed_types'
                """, Boolean.class, mime));
    }

    public boolean lockCase(UUID caseId) {
        return !jdbc.query("SELECT id FROM casework.cases WHERE id = ? FOR UPDATE",
                (row, index) -> row.getObject(1, UUID.class), caseId).isEmpty();
    }

    public boolean withinCaseLimit(UUID caseId, long addedBytes, UploadPolicy policy) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT count(*) < ? AND COALESCE(sum(size_bytes), 0) + ? <= ?
                FROM casework.files WHERE case_id = ? AND removed_at IS NULL
                """, Boolean.class, policy.maxFiles(), addedBytes, policy.maxCaseBytes(), caseId));
    }

    public void save(UUID caseId, UUID userId, StoredOriginal stored, String name, String mime, int pages, int hours) {
        jdbc.update("""
                INSERT INTO casework.files(id, case_id, uploaded_by, file_type, original_name, mime_type,
                    size_bytes, storage_bucket, object_key, sha256, page_count, lifecycle_status, storage_expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'local-temp', ?, ?, ?, 'UPLOADED',
                        clock_timestamp() + make_interval(hours => ?))
                """, stored.id(), caseId, userId, mime.equals("application/pdf") ? "document" : "image",
                name, mime, stored.sizeBytes(), stored.id() + ".upload", stored.sha256(), pages, hours);
    }

    public Optional<FileResponse> find(UUID caseId, UUID fileId) {
        return jdbc.query("""
                SELECT id, case_id, original_name, mime_type, size_bytes, page_count, lifecycle_status,
                       malware_status, purge_status, created_at, storage_expires_at
                FROM casework.files WHERE id = ? AND case_id = ? AND removed_at IS NULL
                """, (row, index) -> FileResponse.builder()
                .id(row.getObject("id", UUID.class))
                .caseId(row.getObject("case_id", UUID.class))
                .originalName(row.getString("original_name"))
                .mimeType(row.getString("mime_type"))
                .sizeBytes(row.getLong("size_bytes"))
                .pageCount(row.getObject("page_count", Integer.class))
                .lifecycleStatus(row.getString("lifecycle_status"))
                .malwareStatus(row.getString("malware_status"))
                .purgeStatus(row.getString("purge_status"))
                .createdAt(row.getTimestamp("created_at").toInstant())
                .storageExpiresAt(row.getTimestamp("storage_expires_at").toInstant())
                .build(), fileId, caseId).stream().findFirst();
    }

    public void recordUploadEvent(UUID caseId, UUID fileId) {
        jdbc.update("""
                INSERT INTO ops.outbox_events(event_type, aggregate_type, aggregate_id, payload,
                    idempotency_key, retention_expires_at)
                VALUES ('FILE_UPLOADED', 'file', ?, jsonb_build_object('caseId', ?::text, 'fileId', ?::text),
                        ?, now() + interval '30 days')
                """, fileId, caseId, fileId, "file-uploaded:" + fileId);
    }
}
