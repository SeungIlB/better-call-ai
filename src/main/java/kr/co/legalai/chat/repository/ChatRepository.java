package kr.co.legalai.chat.repository;

import kr.co.legalai.chat.entity.ChatTurn;
import kr.co.legalai.chat.entity.GeneratedAnswer;
import kr.co.legalai.common.exception.CaseNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 호출은 반드시 사용자 범위 트랜잭션 안에서 실행한다. */
@Repository
@RequiredArgsConstructor
public class ChatRepository {
    private final JdbcTemplate jdbc;
    private static final RowMapper<ChatTurn> MAPPER = (rs, row) -> ChatTurn.builder()
            .id(rs.getObject("id", UUID.class)).turnNo(rs.getLong("turn_no"))
            .question(rs.getString("question")).answer(rs.getString("answer"))
            .status(rs.getString("status")).attempt(rs.getInt("attempt"))
            .createdAt(rs.getTimestamp("created_at").toInstant())
            .answeredAt(rs.getTimestamp("answered_at") == null ? null : rs.getTimestamp("answered_at").toInstant())
            .build();

    public void lockOwner(UUID userId) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 17))", rs -> { }, userId.toString());
    }

    public void lockCase(UUID caseId) {
        if (jdbc.query("SELECT id FROM casework.cases WHERE id = ? FOR UPDATE",
                (rs, row) -> rs.getObject(1, UUID.class), caseId).isEmpty()) {
            throw new CaseNotFoundException();
        }
    }

    public void expireAbandoned() {
        jdbc.update("""
                UPDATE casework.chat_turns SET status = 'FAILED', error_code = 'INTERRUPTED'
                WHERE status = 'RUNNING' AND started_at < now() - interval '3 minutes'
                """);
    }

    public Optional<ChatTurn> find(UUID caseId, UUID id) {
        return jdbc.query("SELECT * FROM casework.chat_turns WHERE case_id = ? AND id = ?",
                MAPPER, caseId, id).stream().findFirst();
    }

    public boolean hasActiveTurn() {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM casework.chat_turns WHERE status = 'RUNNING')", Boolean.class));
    }

    public void insert(UUID caseId, UUID id, UUID userId, String content) {
        jdbc.update("""
                INSERT INTO casework.chat_turns(case_id, id, owner_user_id, question, status)
                VALUES (?, ?, ?, ?, 'RUNNING')
                """, caseId, id, userId, content);
    }

    public long latestNumber(UUID caseId) {
        return jdbc.queryForObject("SELECT coalesce(max(turn_no), 0) FROM casework.chat_turns WHERE case_id = ?",
                Long.class, caseId);
    }

    public void retry(UUID caseId, UUID id) {
        jdbc.update("""
                UPDATE casework.chat_turns SET status = 'RUNNING', attempt = attempt + 1,
                    started_at = now(), error_code = NULL
                WHERE case_id = ? AND id = ? AND status = 'FAILED'
                """, caseId, id);
    }

    public List<ChatTurn> context(UUID caseId, long before) {
        return jdbc.query("""
                SELECT * FROM casework.chat_turns WHERE case_id = ? AND turn_no < ? AND status = 'COMPLETED'
                ORDER BY turn_no DESC LIMIT 5
                """, MAPPER, caseId, before);
    }

    public List<ChatTurn> page(UUID caseId, int page, int size) {
        return jdbc.query("""
                SELECT * FROM casework.chat_turns WHERE case_id = ?
                ORDER BY turn_no DESC LIMIT ? OFFSET ?
                """, MAPPER, caseId, size + 1, ((long) page - 1) * size);
    }

    public boolean complete(UUID caseId, UUID id, int attempt, GeneratedAnswer answer) {
        return jdbc.update("""
                UPDATE casework.chat_turns SET answer = ?, status = 'COMPLETED', answered_at = now(),
                    model_name = ?, provider_response_id = ?, input_tokens = ?, output_tokens = ?
                WHERE case_id = ? AND id = ? AND attempt = ? AND status = 'RUNNING'
                  AND started_at >= now() - interval '3 minutes'
                """, answer.text(), answer.model(), answer.responseId(), answer.inputTokens(), answer.outputTokens(),
                caseId, id, attempt) == 1;
    }

    public void fail(UUID caseId, UUID id, int attempt, String code) {
        jdbc.update("""
                UPDATE casework.chat_turns SET status = 'FAILED', error_code = ?
                WHERE case_id = ? AND id = ? AND attempt = ? AND status = 'RUNNING'
                """, code, caseId, id, attempt);
    }
}
