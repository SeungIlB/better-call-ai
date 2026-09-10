package kr.co.legalai.file.repository;

import kr.co.legalai.file.dto.response.ConfirmedEvidenceResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class ConfirmedEvidenceRepository {
    private final JdbcTemplate jdbc;

    public Optional<Integer> lockVersion(UUID caseId) {
        // 짧은 조회 동안만 확정·사건 삭제와 직렬화해 버전과 본문을 함께 읽는다.
        return jdbc.query("SELECT version_no FROM casework.cases WHERE id = ? AND deleted_at IS NULL FOR SHARE",
                (row, index) -> row.getInt("version_no"), caseId).stream().findFirst();
    }

    public List<ConfirmedEvidenceResponse> findPage(UUID caseId, int page, int size) {
        return jdbc.query("""
                SELECT f.id AS file_id, r.id AS revision_id, r.corrected_text, r.confirmed_at
                FROM casework.files f JOIN casework.ocr_text_revisions r
                  ON r.file_id = f.id AND r.id = f.current_ocr_revision_id
                WHERE f.case_id = ? AND f.removed_at IS NULL AND r.is_current AND r.confirmed_at IS NOT NULL
                ORDER BY r.confirmed_at DESC, f.id DESC LIMIT ? OFFSET ?
                """, (row, index) -> ConfirmedEvidenceResponse.builder()
                .fileId(row.getObject("file_id", UUID.class)).revisionId(row.getObject("revision_id", UUID.class))
                .correctedText(row.getString("corrected_text")).confirmedAt(row.getTimestamp("confirmed_at").toInstant()).build(),
                caseId, size + 1, ((long) page - 1) * size);
    }
}
