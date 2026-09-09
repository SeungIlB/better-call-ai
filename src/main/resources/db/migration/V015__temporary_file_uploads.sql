ALTER TABLE casework.files
    ADD COLUMN page_count integer CHECK (page_count BETWEEN 1 AND 30);
COMMENT ON COLUMN casework.files.page_count IS '검증한 PDF 실제 페이지 수. 이미지는 1이며 기존 미검증 메타데이터는 NULL.';

ALTER TABLE casework.files DROP CONSTRAINT files_lifecycle_status_check;
ALTER TABLE casework.files ADD CONSTRAINT files_lifecycle_status_check CHECK (
    lifecycle_status IN ('UPLOADING','UPLOADED','PROCESSING','REVIEW_REQUIRED','CONFIRMED','PURGE_PENDING','PURGED','FAILED')
);

-- RLS를 우회하는 범위는 만료되었거나 삭제된 사건의 로컬 원본 정리로 제한한다.
CREATE FUNCTION casework.pending_local_file_purges()
RETURNS SETOF uuid LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog AS $$
    SELECT f.id FROM casework.files f JOIN casework.cases c ON c.id = f.case_id
    WHERE f.storage_bucket = 'local-temp' AND f.object_key = f.id::text || '.upload'
      AND f.purge_status IN ('scheduled','retrying')
      AND (f.storage_expires_at <= now() OR c.deleted_at IS NOT NULL)
      AND (f.purge_next_retry_at IS NULL OR f.purge_next_retry_at <= now())
    ORDER BY f.storage_expires_at, f.id LIMIT 100
$$;

CREATE FUNCTION casework.record_local_file_purge(p_file_id uuid, p_success boolean)
RETURNS void LANGUAGE sql VOLATILE SECURITY DEFINER SET search_path = pg_catalog AS $$
    UPDATE casework.files f
    SET storage_bucket = CASE WHEN p_success THEN NULL ELSE f.storage_bucket END,
        object_key = CASE WHEN p_success THEN NULL ELSE f.object_key END,
        lifecycle_status = CASE WHEN p_success THEN 'PURGED' ELSE 'PURGE_PENDING' END,
        purged_at = CASE WHEN p_success THEN now() ELSE NULL END,
        purge_status = CASE WHEN p_success THEN 'purged'
                            WHEN f.purge_attempt_count + 1 >= 5 THEN 'failed' ELSE 'retrying' END,
        purge_attempt_count = f.purge_attempt_count + 1,
        purge_next_retry_at = CASE WHEN p_success OR f.purge_attempt_count + 1 >= 5 THEN NULL
                                  ELSE now() + interval '5 minutes' END,
        purge_error_code = CASE WHEN p_success THEN NULL ELSE 'LOCAL_DELETE_FAILED' END
    FROM casework.cases c
    WHERE f.id = p_file_id AND f.case_id = c.id
      AND f.storage_bucket = 'local-temp' AND f.object_key = f.id::text || '.upload'
      AND f.purge_status IN ('scheduled','retrying')
      AND (f.storage_expires_at <= now() OR c.deleted_at IS NOT NULL)
      AND (f.purge_next_retry_at IS NULL OR f.purge_next_retry_at <= now())
$$;

CREATE FUNCTION casework.local_original_is_tracked(p_file_id uuid)
RETURNS boolean LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog AS $$
    SELECT EXISTS (SELECT 1 FROM casework.files
                   WHERE id = p_file_id AND storage_bucket = 'local-temp'
                     AND object_key = id::text || '.upload')
$$;

COMMENT ON FUNCTION casework.pending_local_file_purges() IS '원본 정리 전용 후보 UUID 최대 100개. 본문·사용자 정보는 반환하지 않는다.';
COMMENT ON FUNCTION casework.record_local_file_purge(uuid, boolean) IS '원본 삭제 확인 후 상태 기록. 실패는 5분 간격 최대 5회이며 이후 운영 확인이 필요하다.';
COMMENT ON FUNCTION casework.local_original_is_tracked(uuid) IS '24시간 지난 고아 로컬 원본 정리에 사용하는 DB 연결 여부 확인.';
REVOKE ALL ON FUNCTION casework.pending_local_file_purges() FROM PUBLIC;
REVOKE ALL ON FUNCTION casework.record_local_file_purge(uuid, boolean) FROM PUBLIC;
REVOKE ALL ON FUNCTION casework.local_original_is_tracked(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION casework.pending_local_file_purges() TO legal_ai_app;
GRANT EXECUTE ON FUNCTION casework.record_local_file_purge(uuid, boolean) TO legal_ai_app;
GRANT EXECUTE ON FUNCTION casework.local_original_is_tracked(uuid) TO legal_ai_app;
