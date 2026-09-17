-- 상품 정책과 시스템 보호 설정은 별도로 관리한다. 앱 계정은 플랜을 변경할 수 없다.
CREATE TABLE ops.upload_plan_limits (
    plan_code varchar(20) PRIMARY KEY CHECK (plan_code IN ('FREE','PAID')),
    daily_upload_limit integer CHECK (daily_upload_limit > 0)
);
COMMENT ON TABLE ops.upload_plan_limits IS '업로드 상품 정책. 무료 하루 10회, 유료 일일 횟수 제한 없음. 파일별·동시 처리 제한은 별도.';
COMMENT ON COLUMN ops.upload_plan_limits.plan_code IS '무료 또는 유료 상품 코드.';
COMMENT ON COLUMN ops.upload_plan_limits.daily_upload_limit IS 'UTC 하루 업로드 예약 횟수. NULL은 상품상 무제한.';
INSERT INTO ops.upload_plan_limits VALUES ('FREE', 10), ('PAID', NULL);
GRANT SELECT ON ops.upload_plan_limits TO legal_ai_app;

CREATE TABLE identity.user_plans (
    user_id uuid PRIMARY KEY REFERENCES identity.users(id) ON DELETE CASCADE,
    plan_code varchar(20) NOT NULL REFERENCES ops.upload_plan_limits(plan_code),
    expires_at timestamptz,
    CHECK (plan_code <> 'PAID' OR expires_at IS NOT NULL)
);
COMMENT ON TABLE identity.user_plans IS '서버 관리 사용자 상품 권한. 행이 없거나 유료 기간이 만료되면 무료로 처리한다.';
COMMENT ON COLUMN identity.user_plans.user_id IS '상품 권한 소유자. 클라이언트 요청이나 JWT 플랜 주장을 신뢰하지 않는다.';
COMMENT ON COLUMN identity.user_plans.plan_code IS '서버가 검증한 상품 코드. 결제 연동 전에는 운영 DB 역할만 변경한다.';
COMMENT ON COLUMN identity.user_plans.expires_at IS '유료 권한 만료 시각. 만료 즉시 무료 한도로 복귀한다.';
ALTER TABLE identity.user_plans ENABLE ROW LEVEL SECURITY;
ALTER TABLE identity.user_plans FORCE ROW LEVEL SECURITY;
CREATE POLICY user_plan_read ON identity.user_plans FOR SELECT USING (user_id = identity.current_user_id());
GRANT SELECT ON identity.user_plans TO legal_ai_app;

CREATE TABLE casework.daily_upload_usage (
    user_id uuid NOT NULL REFERENCES identity.users(id) ON DELETE CASCADE,
    usage_date date NOT NULL,
    attempts integer NOT NULL CHECK (attempts >= 0),
    size_bytes bigint NOT NULL CHECK (size_bytes >= 0),
    PRIMARY KEY (user_id, usage_date)
);
COMMENT ON TABLE casework.daily_upload_usage IS 'UTC 일자별 업로드 예약 사용량. 실패와 사건 삭제에도 차감량을 유지한다.';
COMMENT ON COLUMN casework.daily_upload_usage.user_id IS 'JWT sub에서 얻은 사용자. 일일 한도는 사건 전체에 적용한다.';
COMMENT ON COLUMN casework.daily_upload_usage.usage_date IS 'DB 시각 기준 UTC 사용일. 한국 시각 오전 9시에 날짜가 변경된다.';
COMMENT ON COLUMN casework.daily_upload_usage.attempts IS '검사 전 예약한 요청 수. 무료 상품 한도에만 사용하며 유료 일일 상한은 없다.';
COMMENT ON COLUMN casework.daily_upload_usage.size_bytes IS '예약한 실제 파일 바이트 합계. 사용량 기록용이며 일일 용량 제한에는 사용하지 않는다.';
ALTER TABLE casework.daily_upload_usage ENABLE ROW LEVEL SECURITY;
ALTER TABLE casework.daily_upload_usage FORCE ROW LEVEL SECURITY;
CREATE POLICY daily_upload_self ON casework.daily_upload_usage
    USING (user_id = identity.current_user_id()) WITH CHECK (user_id = identity.current_user_id());
GRANT SELECT, INSERT, UPDATE, DELETE ON casework.daily_upload_usage TO legal_ai_app;

CREATE TABLE casework.upload_requests (
    user_id uuid NOT NULL REFERENCES identity.users(id) ON DELETE CASCADE,
    idempotency_key uuid NOT NULL,
    case_id uuid NOT NULL REFERENCES casework.cases(id) ON DELETE CASCADE,
    fingerprint char(64) NOT NULL,
    file_id uuid NOT NULL UNIQUE,
    status varchar(20) NOT NULL DEFAULT 'PROCESSING'
        CHECK (status IN ('PROCESSING','COMPLETED','FAILED')),
    error_code varchar(80),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (user_id, idempotency_key),
    CHECK ((status = 'FAILED') = (error_code IS NOT NULL))
);
COMMENT ON TABLE casework.upload_requests IS '사용자별 업로드 멱등성 기록. 원본·토큰 없이 결과와 실패 코드를 보관한다.';
COMMENT ON COLUMN casework.upload_requests.user_id IS 'JWT sub에서 얻은 요청 소유자.';
COMMENT ON COLUMN casework.upload_requests.idempotency_key IS '사용자가 요청별 생성한 UUID. 같은 사용자의 다른 사건에서도 재사용할 수 없다.';
COMMENT ON COLUMN casework.upload_requests.case_id IS '요청 대상 사건. 사건의 영구 삭제 시 기록도 제거한다.';
COMMENT ON COLUMN casework.upload_requests.fingerprint IS '사건 ID·파일명·정규화 MIME·실제 파일 SHA-256으로 계산한 요청 해시.';
COMMENT ON COLUMN casework.upload_requests.file_id IS '예약 시 생성한 파일 UUID. 저장 전/실패 시 파일 행이 없어 FK를 두지 않는다.';
COMMENT ON COLUMN casework.upload_requests.status IS '처리 중/성공/실패. 처리 중인 키를 자동 재실행하지 않는다.';
COMMENT ON COLUMN casework.upload_requests.error_code IS '실패한 요청을 재현할 공통 ErrorCode 열거형 이름. 외부 응답은 저장하지 않는다.';
COMMENT ON COLUMN casework.upload_requests.created_at IS '최초 예약 시각. timestamptz로 저장한다.';
ALTER TABLE casework.upload_requests ENABLE ROW LEVEL SECURITY;
ALTER TABLE casework.upload_requests FORCE ROW LEVEL SECURITY;
CREATE POLICY upload_request_read ON casework.upload_requests FOR SELECT
    USING (user_id = identity.current_user_id());
CREATE POLICY upload_request_insert ON casework.upload_requests FOR INSERT
    WITH CHECK (user_id = identity.current_user_id() AND casework.can_access_case(case_id));
CREATE POLICY upload_request_update ON casework.upload_requests FOR UPDATE
    USING (user_id = identity.current_user_id()) WITH CHECK (user_id = identity.current_user_id());
GRANT SELECT, INSERT, UPDATE ON casework.upload_requests TO legal_ai_app;
CREATE INDEX upload_requests_case_idx ON casework.upload_requests(case_id);

CREATE FUNCTION casework.consume_daily_upload(p_bytes bigint)
RETURNS text LANGUAGE plpgsql VOLATILE SECURITY INVOKER SET search_path = pg_catalog AS $$
DECLARE
    v_user uuid := identity.current_user_id();
    v_date date := (statement_timestamp() AT TIME ZONE 'UTC')::date;
    v_plan varchar(20) := 'FREE';
    v_product_limit integer;
    v_count integer;
BEGIN
    IF p_bytes IS NULL OR p_bytes NOT BETWEEN 1 AND 20971520 THEN
        RAISE EXCEPTION 'Invalid upload size' USING ERRCODE = '22023';
    END IF;
    SELECT COALESCE((SELECT plan_code FROM identity.user_plans
        WHERE user_id = v_user AND (expires_at IS NULL OR expires_at > statement_timestamp())), 'FREE')
        INTO v_plan;
    SELECT daily_upload_limit INTO STRICT v_product_limit
        FROM ops.upload_plan_limits WHERE plan_code = v_plan;
    INSERT INTO casework.daily_upload_usage VALUES (v_user, v_date, 0, 0)
        ON CONFLICT (user_id, usage_date) DO NOTHING;
    SELECT attempts INTO v_count FROM casework.daily_upload_usage
        WHERE user_id = v_user AND usage_date = v_date FOR UPDATE;
    IF v_product_limit IS NOT NULL AND v_count >= v_product_limit THEN
        RETURN 'PLAN_LIMIT';
    END IF;
    UPDATE casework.daily_upload_usage SET attempts = attempts + 1, size_bytes = size_bytes + p_bytes
        WHERE user_id = v_user AND usage_date = v_date;
    RETURN 'OK';
END
$$;
COMMENT ON FUNCTION casework.consume_daily_upload(bigint) IS '원자적 업로드 예약. 무료 상품 한도만 적용하고 유료는 일일 횟수·용량 상한 없이 기록하며 RLS를 준수한다.';
REVOKE ALL ON FUNCTION casework.consume_daily_upload(bigint) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION casework.consume_daily_upload(bigint) TO legal_ai_app;
