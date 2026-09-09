CREATE TABLE identity.login_attempts (
    email_lookup_hash char(64) PRIMARY KEY CHECK (email_lookup_hash ~ '^[0-9a-f]{64}$'),
    failed_attempts smallint NOT NULL DEFAULT 0 CHECK (failed_attempts BETWEEN 0 AND 5),
    locked_until timestamptz,
    updated_at timestamptz NOT NULL DEFAULT now(),
    CHECK ((failed_attempts = 5) = (locked_until IS NOT NULL))
);

CREATE INDEX ix_login_attempts_updated_at ON identity.login_attempts(updated_at);

COMMENT ON TABLE identity.login_attempts IS
    '가입 여부와 무관한 이메일별 로그인 실패 제한. 이메일 원문 없이 HMAC 조회값만 저장한다.';
COMMENT ON COLUMN identity.login_attempts.email_lookup_hash IS '정규화 이메일의 HMAC-SHA256. 사용자 계정 삭제 시에도 이 값으로 삭제한다.';
COMMENT ON COLUMN identity.login_attempts.failed_attempts IS '마지막 성공 또는 잠금 만료 이후 연속 실패 횟수. 다섯 번째 실패부터 차단한다.';
COMMENT ON COLUMN identity.login_attempts.locked_until IS '다섯 번째 실패부터 15분 잠금. 차단된 요청은 잠금 기간을 연장하지 않는다.';
COMMENT ON COLUMN identity.login_attempts.updated_at IS '마지막 허용된 시도 시각. 24시간 이상 미사용 상태는 정리 가능하다.';

REVOKE ALL ON identity.login_attempts FROM PUBLIC;
GRANT SELECT, INSERT, UPDATE, DELETE ON identity.login_attempts TO legal_ai_auth;
