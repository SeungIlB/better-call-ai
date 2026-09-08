DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'legal_ai_app') THEN
    CREATE ROLE legal_ai_app LOGIN PASSWORD 'app_dev_only' NOBYPASSRLS;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'legal_ai_auth') THEN
    CREATE ROLE legal_ai_auth LOGIN PASSWORD 'auth_dev_only' BYPASSRLS;
  END IF;
END
$$;

CREATE EXTENSION IF NOT EXISTS pgcrypto;
CREATE EXTENSION IF NOT EXISTS citext;
CREATE EXTENSION IF NOT EXISTS vector;
