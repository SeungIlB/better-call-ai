package kr.co.legalai.casework.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public class AnalysisRepository {
    private final JdbcTemplate jdbcTemplate;

    public AnalysisRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void markAllCurrentRunsStale(UUID caseId) {
        jdbcTemplate.update("""
                        UPDATE aiops.analysis_runs
                        SET stale_at = now(), stale_reason = 'case_input_changed'
                        WHERE case_id = ? AND stale_at IS NULL
                        """,
                caseId
        );
    }
}
