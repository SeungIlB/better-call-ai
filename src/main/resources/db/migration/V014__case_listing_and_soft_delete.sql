CREATE INDEX ix_cases_owner_updated_page
    ON casework.cases(owner_user_id, updated_at DESC, id DESC)
    WHERE deleted_at IS NULL;

COMMENT ON INDEX casework.ix_cases_owner_updated_page IS
    '사용자별 활성 사건 목록의 최근 수정순 조회. 동일 시각은 UUID 역순으로 정렬한다.';

ALTER TABLE casework.cases ADD CONSTRAINT ck_case_delete_deadline
    CHECK (deleted_at IS NULL OR hard_delete_after >= deleted_at);

-- 삭제 표시된 행은 SELECT RLS에서 즉시 숨겨진다.
-- 이를 위해 SELECT 정책을 넓히지 않고 삭제 전환만 한정된 함수로 실행한다.
-- 기존 can_access_case와 동일하게 RLS를 우회할 수 있는 migration 소유자로 실행한다.
CREATE FUNCTION casework.soft_delete_case(p_case_id uuid)
RETURNS integer
LANGUAGE sql VOLATILE SECURITY DEFINER
SET search_path = pg_catalog
AS $$
    UPDATE casework.cases
    SET deleted_at = now(),
        hard_delete_after = now() + make_interval(days => (
            SELECT (value_json #>> '{}')::integer FROM ops.runtime_settings
            WHERE setting_key = 'case.delete_grace_days'
        )),
        current_analysis_run_id = NULL,
        version_no = version_no + 1
    WHERE id = p_case_id
      AND owner_user_id = identity.current_user_id()
      AND deleted_at IS NULL
    RETURNING version_no
$$;

COMMENT ON FUNCTION casework.soft_delete_case(uuid) IS
    '현재 인증 사용자의 활성 사건만 삭제 표시하고 새 버전을 반환한다. 없는 사건·타인 사건·기삭제 사건은 NULL.';
REVOKE ALL ON FUNCTION casework.soft_delete_case(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION casework.soft_delete_case(uuid) TO legal_ai_app;
