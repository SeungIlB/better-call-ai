package kr.co.legalai.casework.persistence

import kr.co.legalai.casework.api.CreateCaseRequest
import kr.co.legalai.casework.domain.CaseRecord
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.util.UUID

@Repository
class CaseRepository(
    private val jdbc: JdbcTemplate,
) {
    fun insert(id: UUID, ownerId: UUID, request: CreateCaseRequest) {
        jdbc.update(
            """
            INSERT INTO casework.cases(
                id, owner_user_id, title, user_party_role, user_goal, original_statement
            ) VALUES (?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            id,
            ownerId,
            request.title.trim(),
            request.userPartyRole?.trim(),
            request.userGoal?.trim(),
            request.originalStatement?.trim(),
        )
    }

    fun find(id: UUID): CaseRecord? =
        jdbc.query(
            """
            SELECT id, title, status, user_party_role, user_goal, original_statement,
                   version_no, created_at, updated_at
            FROM casework.cases
            WHERE id = ?
            """.trimIndent(),
            rowMapper,
            id,
        ).firstOrNull()

    fun updateStatement(id: UUID, statement: String, expectedVersion: Int): Int =
        jdbc.update(
            """
            UPDATE casework.cases
            SET original_statement = ?,
                status = 'COLLECTING',
                version_no = version_no + 1
            WHERE id = ? AND version_no = ?
            """.trimIndent(),
            statement.trim(),
            id,
            expectedVersion,
        )

    fun markAnalysisStale(caseId: UUID) {
        jdbc.update(
            """
            UPDATE aiops.analysis_runs
            SET stale_at = now(), stale_reason = 'case_input_changed'
            WHERE case_id = ? AND stale_at IS NULL
            """.trimIndent(),
            caseId,
        )
    }

    fun enqueue(
        eventId: UUID,
        eventType: String,
        caseId: UUID,
        idempotencyKey: String,
        version: Int,
    ) {
        jdbc.update(
            """
            INSERT INTO ops.outbox_events(
                id, event_type, aggregate_type, aggregate_id, payload,
                idempotency_key, retention_expires_at
            ) VALUES (
                ?, ?, 'case', ?,
                jsonb_build_object('caseId', ?::text, 'version', ?),
                ?, now() + interval '30 days'
            )
            """.trimIndent(),
            eventId,
            eventType,
            caseId,
            caseId,
            version,
            idempotencyKey,
        )
    }

    private val rowMapper = RowMapper { rs: ResultSet, _: Int ->
        CaseRecord(
            id = rs.getObject("id", UUID::class.java),
            title = rs.getString("title"),
            status = rs.getString("status"),
            userPartyRole = rs.getString("user_party_role"),
            userGoal = rs.getString("user_goal"),
            originalStatement = rs.getString("original_statement"),
            version = rs.getInt("version_no"),
            createdAt = rs.getTimestamp("created_at").toInstant(),
            updatedAt = rs.getTimestamp("updated_at").toInstant(),
        )
    }
}
