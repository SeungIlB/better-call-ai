CREATE SCHEMA IF NOT EXISTS billing;

CREATE TABLE billing.payment_orders (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES identity.users(id) ON DELETE RESTRICT,
    order_id varchar(64) NOT NULL UNIQUE,
    plan_code varchar(20) NOT NULL REFERENCES ops.upload_plan_limits(plan_code),
    amount bigint NOT NULL CHECK (amount > 0),
    currency varchar(3) NOT NULL DEFAULT 'KRW' CHECK (currency = 'KRW'),
    provider varchar(30) NOT NULL DEFAULT 'toss_payments',
    payment_key varchar(200),
    status varchar(20) NOT NULL DEFAULT 'READY'
        CHECK (status IN ('READY','CONFIRMED','FAILED','CANCELED')),
    expires_at timestamptz NOT NULL,
    confirmed_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX payment_orders_user_idx ON billing.payment_orders(user_id, created_at DESC);
CREATE UNIQUE INDEX payment_orders_payment_key_idx ON billing.payment_orders(payment_key) WHERE payment_key IS NOT NULL;

CREATE TABLE billing.payment_events (
    event_id varchar(200) PRIMARY KEY,
    order_id varchar(64) NOT NULL REFERENCES billing.payment_orders(order_id) ON DELETE RESTRICT,
    event_type varchar(80) NOT NULL,
    payload_hash char(64) NOT NULL,
    received_at timestamptz NOT NULL DEFAULT now()
);

COMMENT ON SCHEMA billing IS '결제 주문·승인·웹훅 멱등성 기록';
COMMENT ON TABLE billing.payment_orders IS '서버가 생성한 결제 주문과 토스 승인 상태';
COMMENT ON TABLE billing.payment_events IS '결제 웹훅 중복 처리를 위한 이벤트 수신 기록';
COMMENT ON COLUMN billing.payment_orders.amount IS '서버 상품표에서 확정한 결제 금액. 클라이언트 금액을 신뢰하지 않는다.';
COMMENT ON COLUMN billing.payment_orders.payment_key IS '토스 승인 후 발급된 결제 키. 원문은 로그에 남기지 않는다.';

GRANT USAGE ON SCHEMA billing TO legal_ai_app;
GRANT SELECT, INSERT, UPDATE ON billing.payment_orders TO legal_ai_app;
GRANT SELECT, INSERT ON billing.payment_events TO legal_ai_app;
