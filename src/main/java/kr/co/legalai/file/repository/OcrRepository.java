package kr.co.legalai.file.repository;

import kr.co.legalai.file.dto.response.OcrRevisionResponse;
import kr.co.legalai.file.entity.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class OcrRepository {
    private final JdbcTemplate jdbc;
    private static final RowMapper<OcrRevisionResponse> REVISION = (row, index) -> OcrRevisionResponse.builder()
            .id(row.getObject("id", UUID.class)).extractionId(row.getObject("extraction_id", UUID.class))
            .revision(row.getInt("revision_no")).correctedText(row.getString("corrected_text"))
            .current(row.getBoolean("is_current"))
            .confirmedAt(row.getTimestamp("confirmed_at") == null ? null : row.getTimestamp("confirmed_at").toInstant())
            .createdAt(row.getTimestamp("created_at").toInstant()).build();

    public Optional<OcrFile> lockFile(UUID caseId, UUID fileId) {
        return jdbc.query("""
                SELECT id, mime_type, size_bytes, sha256, current_extraction_id, current_ocr_revision_id,
                       (malware_status = 'clean' AND storage_bucket = 'local-temp'
                        AND object_key = id::text || '.upload' AND storage_expires_at > clock_timestamp()) AS available
                FROM casework.files WHERE case_id = ? AND id = ? AND removed_at IS NULL FOR UPDATE
                """, (row, i) -> OcrFile.builder().id(row.getObject("id", UUID.class))
                .mimeType(row.getString("mime_type")).sizeBytes(row.getLong("size_bytes"))
                .sha256(row.getString("sha256")).extractionId(row.getObject("current_extraction_id", UUID.class))
                .revisionId(row.getObject("current_ocr_revision_id", UUID.class))
                .available(row.getBoolean("available")).build(),
                caseId, fileId).stream().findFirst();
    }

    public Optional<OcrExtraction> extraction(UUID fileId, UUID id) {
        return jdbc.query("SELECT id, status, raw_text FROM casework.file_extractions WHERE file_id = ? AND id = ?",
                (row, i) -> OcrExtraction.builder().id(row.getObject("id", UUID.class))
                        .status(row.getString("status")).rawText(row.getString("raw_text")).build(),
                fileId, id).stream().findFirst();
    }

    public Optional<OcrExtraction> byKey(UUID fileId, UUID key) {
        return jdbc.query("SELECT id, status, raw_text FROM casework.file_extractions WHERE file_id = ? AND idempotency_key = ?",
                (row, i) -> OcrExtraction.builder().id(row.getObject("id", UUID.class))
                        .status(row.getString("status")).rawText(row.getString("raw_text")).build(),
                fileId, requestKey(fileId, key)).stream().findFirst();
    }

    public void expire(UUID fileId) {
        jdbc.update("""
                UPDATE casework.file_extractions SET status = 'failed', completed_at = clock_timestamp(),
                    error_code = 'OCR_ABANDONED'
                WHERE file_id = ? AND status = 'running' AND started_at <= clock_timestamp() - interval '3 minutes'
                """, fileId);
        jdbc.update("""
                UPDATE casework.files f SET lifecycle_status = 'FAILED'
                WHERE f.id = ? AND f.lifecycle_status = 'PROCESSING'
                  AND EXISTS (SELECT 1 FROM casework.file_extractions e
                              WHERE e.id = f.current_extraction_id AND e.status = 'failed')
                """, fileId);
    }

    public void reserve(UUID fileId, UUID id, UUID key, String model, UUID userId) {
        jdbc.update("""
                INSERT INTO identity.user_consents(user_id, consent_type, policy_version, granted, granted_at, metadata)
                VALUES (?, 'external_ocr', 'openai-ocr-v1', true, clock_timestamp(), '{"provider":"openai"}'::jsonb)
                ON CONFLICT (user_id, consent_type, policy_version)
                DO UPDATE SET granted = true, granted_at = EXCLUDED.granted_at, revoked_at = NULL
                """, userId);
        jdbc.update("""
                INSERT INTO casework.file_extractions(id, file_id, idempotency_key, extraction_type, provider,
                    model_name, status, started_at)
                VALUES (?, ?, ?, 'ocr', 'openai', ?, 'running', clock_timestamp())
                """, id, fileId, requestKey(fileId, key), model);
        jdbc.update("UPDATE casework.files SET current_extraction_id = ?, lifecycle_status = 'PROCESSING' WHERE id = ?",
                id, fileId);
    }

    public boolean complete(UUID fileId, UUID id, OcrText text) {
        int count = jdbc.update("""
                UPDATE casework.file_extractions SET raw_text = ?, model_name = ?, provider_response_id = ?,
                    input_tokens = ?, output_tokens = ?, status = 'succeeded', completed_at = clock_timestamp()
                WHERE file_id = ? AND id = ? AND status = 'running'
                  AND started_at > clock_timestamp() - interval '3 minutes'
                """, text.text(), text.model(), text.responseId(), text.inputTokens(), text.outputTokens(), fileId, id);
        if (count == 1) {
            jdbc.update("UPDATE casework.files SET lifecycle_status = 'REVIEW_REQUIRED' WHERE id = ?", fileId);
        }
        return count == 1;
    }

    public void fail(UUID fileId, UUID id) {
        jdbc.update("""
                UPDATE casework.file_extractions SET status = 'failed', error_code = 'OCR_FAILED',
                    completed_at = clock_timestamp() WHERE file_id = ? AND id = ? AND status = 'running'
                """, fileId, id);
        jdbc.update("""
                UPDATE casework.files SET lifecycle_status = 'FAILED'
                WHERE id = ? AND current_extraction_id = ? AND lifecycle_status = 'PROCESSING'
                """, fileId, id);
    }

    public Optional<OcrRevisionResponse> latest(UUID fileId) {
        return jdbc.query("""
                SELECT r.* FROM casework.ocr_text_revisions r JOIN casework.files f ON f.id = r.file_id
                WHERE r.file_id = ? AND r.extraction_id = f.current_extraction_id ORDER BY r.revision_no DESC LIMIT 1
                """, REVISION, fileId).stream().findFirst();
    }

    public Optional<OcrRevisionResponse> revision(UUID fileId, UUID id) {
        return jdbc.query("SELECT * FROM casework.ocr_text_revisions WHERE file_id = ? AND id = ?",
                REVISION, fileId, id).stream().findFirst();
    }

    public Optional<OcrRevisionResponse> revisionNumber(UUID fileId, UUID extractionId, int number) {
        return jdbc.query("SELECT * FROM casework.ocr_text_revisions WHERE file_id = ? AND extraction_id = ? AND revision_no = ?",
                REVISION, fileId, extractionId, number).stream().findFirst();
    }

    public OcrRevisionResponse save(UUID fileId, UUID extractionId, int number, String text, UUID userId) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO casework.ocr_text_revisions(id, file_id, extraction_id, revision_no, corrected_text,
                    created_by, is_current) VALUES (?, ?, ?, ?, ?, ?, false)
                """, id, fileId, extractionId, number, text, userId);
        return revision(fileId, id).orElseThrow();
    }

    public List<OcrRevisionResponse> history(UUID fileId, int page, int size) {
        return jdbc.query("""
                SELECT * FROM casework.ocr_text_revisions WHERE file_id = ?
                ORDER BY created_at DESC, revision_no DESC LIMIT ? OFFSET ?
                """, REVISION, fileId, size + 1, ((long) page - 1) * size);
    }

    public void confirm(UUID caseId, OcrFile file, UUID revisionId) {
        jdbc.update("UPDATE casework.ocr_text_revisions SET is_current = false WHERE file_id = ? AND is_current", file.id());
        jdbc.update("""
                UPDATE casework.ocr_text_revisions SET is_current = true, confirmed_at = clock_timestamp()
                WHERE file_id = ? AND id = ?
                """, file.id(), revisionId);
        // 보관 기한을 지금으로 앞당겨, 커밋 직후 프로세스가 종료돼도 기존 정리 작업이 원본을 회수한다.
        jdbc.update("""
                UPDATE casework.files SET current_ocr_revision_id = ?, pii_status = 'reviewed',
                    lifecycle_status = CASE WHEN object_key IS NULL THEN 'PURGED' ELSE 'PURGE_PENDING' END,
                    storage_expires_at = LEAST(storage_expires_at, clock_timestamp())
                WHERE id = ?
                """, revisionId, file.id());
        jdbc.update("""
                UPDATE casework.cases SET version_no = version_no + 1, confirmed_at = NULL
                WHERE id = ?
                """, caseId);
        jdbc.update("""
                UPDATE aiops.analysis_runs SET stale_at = clock_timestamp(), stale_reason = 'ocr_changed'
                WHERE case_id = ? AND stale_at IS NULL
                """, caseId);
        jdbc.update("""
                INSERT INTO ops.outbox_events(event_type, aggregate_type, aggregate_id, payload,
                    idempotency_key, retention_expires_at)
                VALUES ('OCR_CONFIRMED', 'file', ?, jsonb_build_object('caseId', ?::text, 'revisionId', ?::text),
                    ?, now() + interval '30 days')
                """, file.id(), caseId, revisionId, "ocr-confirmed:" + revisionId);
    }

    public boolean purgeDue(UUID fileId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM casework.files WHERE id = ? AND storage_bucket = 'local-temp'
                    AND object_key = id::text || '.upload' AND storage_expires_at <= clock_timestamp()
                    AND purge_status IN ('scheduled','retrying')
                    AND (purge_next_retry_at IS NULL OR purge_next_retry_at <= clock_timestamp()))
                """, Boolean.class, fileId));
    }

    private String requestKey(UUID fileId, UUID key) { return "ocr:" + fileId + ":" + key; }
}
