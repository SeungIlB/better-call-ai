package kr.co.legalai.casework.repository;

import kr.co.legalai.casework.dto.request.CreateCaseRequest;
import kr.co.legalai.casework.entity.CaseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * casework.cases 테이블의 사용자 범위 CRUD를 담당한다.
 */
@Repository
public class CaseRepository {
    private static final RowMapper<CaseEntity> ROW_MAPPER = (result, rowNumber) -> new CaseEntity(
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

    private final JdbcTemplate jdbcTemplate;

    public CaseRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void save(UUID caseId, UUID ownerUserId, CreateCaseRequest request) {
        jdbcTemplate.update("""
                        INSERT INTO casework.cases(
                            id, owner_user_id, title, user_party_role, user_goal, original_statement
                        ) VALUES (?, ?, ?, ?, ?, ?)
                        """,
                caseId,
                ownerUserId,
                request.title().trim(),
                trimToNull(request.userPartyRole()),
                trimToNull(request.userGoal()),
                trimToNull(request.originalStatement())
        );
    }

    public Optional<CaseEntity> findById(UUID caseId) {
        return jdbcTemplate.query("""
                        SELECT id, title, status, user_party_role, user_goal, original_statement,
                               version_no, created_at, updated_at
                        FROM casework.cases
                        WHERE id = ?
                        """,
                ROW_MAPPER,
                caseId
        ).stream().findFirst();
    }

    public int update(UUID caseId, String originalStatement, int expectedVersion) {
        return jdbcTemplate.update("""
                        UPDATE casework.cases
                        SET original_statement = ?,
                            status = 'COLLECTING',
                            version_no = version_no + 1
                        WHERE id = ? AND version_no = ?
                        """,
                originalStatement.trim(),
                caseId,
                expectedVersion
        );
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
