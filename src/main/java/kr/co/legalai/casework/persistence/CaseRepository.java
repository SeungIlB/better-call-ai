package kr.co.legalai.casework.persistence;

import kr.co.legalai.casework.api.CreateCaseRequest;
import kr.co.legalai.casework.domain.CaseRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public class CaseRepository {
    private static final RowMapper<CaseRecord> ROW_MAPPER = (result, rowNumber) -> new CaseRecord(
            result.getObject("id", UUID.class),
            result.getString("title"),
            result.getString("status"),
            result.getString("user_party_role"),
            result.getString("user_goal"),
            result.getString("original_statement"),
            result.getInt("version_no"),
            result.getTimestamp("created_at").toInstant(),
            result.getTimestamp("updated_at").toInstant()
    );

    private final JdbcTemplate jdbc;

    public CaseRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(UUID id, UUID ownerId, CreateCaseRequest request) {
        jdbc.update("""
                        INSERT INTO casework.cases(
                            id, owner_user_id, title, user_party_role, user_goal, original_statement
                        ) VALUES (?, ?, ?, ?, ?, ?)
                        """,
                id,
                ownerId,
                request.title().trim(),
                trimToNull(request.userPartyRole()),
                trimToNull(request.userGoal()),
                trimToNull(request.originalStatement())
        );
    }

    public Optional<CaseRecord> find(UUID id) {
        return jdbc.query("""
                        SELECT id, title, status, user_party_role, user_goal, original_statement,
                               version_no, created_at, updated_at
                        FROM casework.cases
                        WHERE id = ?
                        """,
                ROW_MAPPER,
                id
        ).stream().findFirst();
    }

    public int updateStatement(UUID id, String statement, int expectedVersion) {
        return jdbc.update("""
                        UPDATE casework.cases
                        SET original_statement = ?,
                            status = 'COLLECTING',
                            version_no = version_no + 1
                        WHERE id = ? AND version_no = ?
                        """,
                statement.trim(),
                id,
                expectedVersion
        );
    }

    public void markAnalysisStale(UUID caseId) {
        jdbc.update("""
                        UPDATE aiops.analysis_runs
                        SET stale_at = now(), stale_reason = 'case_input_changed'
                        WHERE case_id = ? AND stale_at IS NULL
                        """,
                caseId
        );
    }

    public void enqueue(
            UUID eventId,
            String eventType,
            UUID caseId,
            String idempotencyKey,
            int version
    ) {
        jdbc.update("""
                        INSERT INTO ops.outbox_events(
                            id, event_type, aggregate_type, aggregate_id, payload,
                            idempotency_key, retention_expires_at
                        ) VALUES (
                            ?, ?, 'case', ?,
                            jsonb_build_object('caseId', ?::text, 'version', ?),
                            ?, now() + interval '30 days'
                        )
                        """,
                eventId,
                eventType,
                caseId,
                caseId,
                version,
                idempotencyKey
        );
    }

    private String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
