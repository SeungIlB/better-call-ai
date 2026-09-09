package kr.co.legalai.file.repository;

import lombok.RequiredArgsConstructor;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class FileCleanupRepository {
    private final JdbcTemplate jdbc;


    public List<UUID> candidates() {
        return jdbc.query("SELECT * FROM casework.pending_local_file_purges()",
                (row, index) -> row.getObject(1, UUID.class));
    }

    public void record(UUID fileId, boolean success) {
        jdbc.query("SELECT casework.record_local_file_purge(?, ?)", (row, index) -> 0, fileId, success);
    }

    public boolean isTracked(UUID fileId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT casework.local_original_is_tracked(?)", Boolean.class, fileId));
    }
}
