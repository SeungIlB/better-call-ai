#!/bin/sh
set -eu

: "${DATABASE_APP_PASSWORD:?DATABASE_APP_PASSWORD is required}"
: "${DATABASE_AUTH_PASSWORD:?DATABASE_AUTH_PASSWORD is required}"

psql \
  --username "$POSTGRES_USER" \
  --dbname "$POSTGRES_DB" \
  --set=ON_ERROR_STOP=1 \
  --set=app_password="$DATABASE_APP_PASSWORD" \
  --set=auth_password="$DATABASE_AUTH_PASSWORD" <<-'EOSQL'
SELECT format(
  'CREATE ROLE legal_ai_app LOGIN PASSWORD %L NOBYPASSRLS',
  :'app_password'
)
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'legal_ai_app')
\gexec

SELECT format(
  'CREATE ROLE legal_ai_auth LOGIN PASSWORD %L BYPASSRLS',
  :'auth_password'
)
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'legal_ai_auth')
\gexec

CREATE EXTENSION IF NOT EXISTS pgcrypto;
CREATE EXTENSION IF NOT EXISTS citext;
CREATE EXTENSION IF NOT EXISTS vector;
EOSQL
