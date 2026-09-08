CREATE TABLE identity.users (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    email_enc bytea,
    email_lookup_hash char(64) UNIQUE,
    encryption_key_id varchar(100),
    display_name varchar(100),
    status varchar(20) NOT NULL DEFAULT 'active'
        CHECK (status IN ('active','suspended','withdrawn')),
    last_login_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    deleted_at timestamptz,
    CHECK ((email_enc IS NULL) = (email_lookup_hash IS NULL)),
    CHECK (email_enc IS NULL OR encryption_key_id IS NOT NULL)
);

CREATE TABLE identity.auth_identities (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES identity.users(id) ON DELETE CASCADE,
    provider varchar(30) NOT NULL,
    provider_subject varchar(255) NOT NULL,
    password_hash text,
    status varchar(20) NOT NULL DEFAULT 'active'
        CHECK (status IN ('active','disabled','revoked')),
    disabled_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    last_used_at timestamptz,
    UNIQUE (provider, provider_subject),
    UNIQUE (user_id, provider),
    CHECK (provider <> 'local' OR password_hash IS NOT NULL)
);

CREATE TABLE identity.user_consents (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES identity.users(id) ON DELETE CASCADE,
    consent_type varchar(40) NOT NULL
        CHECK (consent_type IN ('terms','privacy','external_ocr','ai_processing')),
    policy_version varchar(30) NOT NULL,
    granted boolean NOT NULL,
    granted_at timestamptz,
    revoked_at timestamptz,
    metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (user_id, consent_type, policy_version),
    CHECK (NOT granted OR granted_at IS NOT NULL),
    CHECK (revoked_at IS NULL OR NOT granted)
);

