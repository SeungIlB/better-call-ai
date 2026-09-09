package kr.co.legalai.file.repository;

import kr.co.legalai.file.entity.UploadRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class UploadRequestRepository {
    private final JdbcTemplate jdbc;

    public boolean reserve(UUID userId, UUID key, UUID caseId, String fingerprint, UUID fileId) {
        return jdbc.update("""
                INSERT INTO casework.upload_requests(user_id, idempotency_key, case_id, fingerprint, file_id)
                VALUES (?, ?, ?, ?, ?) ON CONFLICT (user_id, idempotency_key) DO NOTHING
                """, userId, key, caseId, fingerprint, fileId) == 1;
    }

    public String consume(long bytes) {
        jdbc.update("""
                DELETE FROM casework.daily_upload_usage
                WHERE usage_date < (statement_timestamp() AT TIME ZONE 'UTC')::date - 30
                """);
        return jdbc.queryForObject("SELECT casework.consume_daily_upload(?)", String.class, bytes);
    }

    public UploadRequest find(UUID key) {
        return jdbc.queryForObject("""
                SELECT file_id, fingerprint, status, error_code FROM casework.upload_requests
                WHERE user_id = identity.current_user_id() AND idempotency_key = ?
                """, (row, index) -> UploadRequest.builder()
                .fileId(row.getObject("file_id", UUID.class)).fingerprint(row.getString("fingerprint"))
                .status(row.getString("status")).errorCode(row.getString("error_code")).build(), key);
    }

    public void finish(UUID key, String status, String errorCode) {
        int updated = jdbc.update("""
                UPDATE casework.upload_requests SET status = ?, error_code = ?
                WHERE user_id = identity.current_user_id() AND idempotency_key = ? AND status = 'PROCESSING'
                """, status, errorCode, key);
        if (updated != 1) {
            throw new IllegalStateException("업로드 예약 상태를 변경할 수 없습니다.");
        }
    }
}
