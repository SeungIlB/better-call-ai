package kr.co.legalai.casework.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/**
 * 도메인 변경과 비동기 외부 작업을 연결하는 outbox event를 저장한다.
 */
@Repository
public class OutboxRepository {
    private final JdbcTemplate jdbcTemplate;

    public OutboxRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void save(
            UUID eventId,
            String eventType,
            UUID caseId,
            String idempotencyKey,
            int version
    ) {
        jdbcTemplate.update("""
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
}
