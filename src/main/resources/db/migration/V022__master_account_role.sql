ALTER TABLE identity.users
    ADD COLUMN account_role varchar(20) NOT NULL DEFAULT 'USER'
    CHECK (account_role IN ('USER', 'MASTER'));

COMMENT ON COLUMN identity.users.account_role IS '운영 계정 역할. 공개 회원가입으로 MASTER를 만들 수 없고 별도 운영 명령으로만 승격한다.';

GRANT SELECT ON identity.users TO legal_ai_app;
