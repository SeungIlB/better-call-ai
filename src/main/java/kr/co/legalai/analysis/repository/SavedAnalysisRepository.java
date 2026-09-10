package kr.co.legalai.analysis.repository;

import kr.co.legalai.analysis.dto.request.AnalysisRequest;
import kr.co.legalai.analysis.dto.response.AnalysisResponse;
import kr.co.legalai.legaldata.dto.response.LegalDraftResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class SavedAnalysisRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private static final String SELECT = """
            SELECT a.*, (a.stale_at IS NOT NULL OR
                (a.display_snapshot->'request'->>'expectedCaseVersion')::int <> c.version_no) AS outdated
            FROM aiops.analysis_runs a JOIN casework.cases c ON c.id=a.case_id
            WHERE a.case_id=? AND c.deleted_at IS NULL AND jsonb_exists(a.display_snapshot, 'request')
            """;

    public Optional<Integer> lockCase(UUID id) {
        return jdbc.query("SELECT version_no FROM casework.cases WHERE id=? AND deleted_at IS NULL FOR UPDATE",
                (r, i) -> r.getInt(1), id).stream().findFirst();
    }

    public void expire(UUID caseId) {
        jdbc.update("""
                UPDATE aiops.analysis_runs SET status='failed', completed_at=now(),
                    display_snapshot=jsonb_set(display_snapshot,'{errorCode}','"ANALYSIS_004"'::jsonb)
                WHERE case_id=? AND status='running' AND started_at < now()-interval '5 minutes'
                    AND jsonb_exists(display_snapshot, 'request')
                """, caseId);
    }

    public boolean running(UUID caseId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM aiops.analysis_runs WHERE case_id=? AND status IN ('queued','running'))",
                Boolean.class, caseId));
    }

    public Optional<Stored> byKey(UUID caseId, String key) {
        return jdbc.query(SELECT + " AND a.idempotency_key=?", this::map, caseId, key).stream().findFirst();
    }

    public Optional<AnalysisResponse> get(UUID caseId, UUID id) {
        return jdbc.query(SELECT + " AND a.id=?", this::map, caseId, id).stream().map(Stored::response).findFirst();
    }

    public List<AnalysisResponse> list(UUID caseId, int page, int size) {
        return jdbc.query(SELECT + " ORDER BY a.version_no DESC LIMIT ? OFFSET ?", this::map,
                caseId, size + 1, (long) (page - 1) * size).stream().map(Stored::response).toList();
    }

    public void reserve(UUID id, UUID caseId, String key, String fingerprint, AnalysisRequest request) {
        jdbc.update("""
                INSERT INTO aiops.analysis_runs(id,case_id,trigger_type,version_no,idempotency_key,input_fingerprint,status,display_snapshot)
                SELECT ?,?,'manual',coalesce(max(version_no),0)+1,?,?,'running',?::jsonb
                FROM aiops.analysis_runs WHERE case_id=?
                """, id, caseId, key, fingerprint, mapper.writeValueAsString(Map.of("request", request)), caseId);
    }

    public boolean complete(UUID caseId, UUID id, LegalDraftResponse draft, List<UUID> fileIds) {
        int count = jdbc.update("""
                UPDATE aiops.analysis_runs SET status='succeeded',completed_at=now(),summary=?,
                    display_snapshot=jsonb_set(display_snapshot,'{result}',?::jsonb)
                WHERE case_id=? AND id=? AND status='running' AND stale_at IS NULL
                """, draft.summary(), mapper.writeValueAsString(draft), caseId, id);
        if (count == 1) {
            jdbc.update("UPDATE casework.cases SET current_analysis_run_id=? WHERE id=?", id, caseId);
            for (var fileId : fileIds) {
                jdbc.update("""
                        INSERT INTO aiops.analysis_run_inputs(analysis_run_id,source_type,source_id,content_hash)
                        SELECT ?,'ocr_revision',f.current_ocr_revision_id,?
                        FROM casework.files f WHERE f.id=? AND f.case_id=? AND f.current_ocr_revision_id IS NOT NULL
                        """, id, kr.co.legalai.legaldata.service.impl.LawArticleParser.hash(draft.evidence().text() + ":" + fileId), fileId, caseId);
            }
        }
        return count == 1;
    }

    public void fail(UUID caseId, UUID id, String code) {
        jdbc.update("""
                UPDATE aiops.analysis_runs SET status='failed',completed_at=now(),
                    display_snapshot=jsonb_set(display_snapshot,'{errorCode}',to_jsonb(?::text))
                WHERE case_id=? AND id=? AND status='running'
                """, code, caseId, id);
    }

    private Stored map(ResultSet row, int index) throws SQLException {
        var snapshot = mapper.readTree(row.getString("display_snapshot"));
        var request = mapper.treeToValue(snapshot.path("request"), AnalysisRequest.class);
        var result = snapshot.has("result") ? mapper.treeToValue(snapshot.path("result"), LegalDraftResponse.class) : null;
        return new Stored(AnalysisResponse.builder().id(row.getObject("id", UUID.class)).caseId(row.getObject("case_id", UUID.class))
                .version(row.getInt("version_no")).status(row.getString("status")).stale(row.getBoolean("outdated"))
                .staleReason(row.getString("stale_reason")).errorCode(snapshot.path("errorCode").asString(null))
                .createdAt(row.getTimestamp("created_at").toInstant()).request(request).result(result).build(), row.getString("input_fingerprint"));
    }

    public record Stored(AnalysisResponse response, String fingerprint) { }
}
