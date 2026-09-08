CREATE TABLE casework.cases (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_user_id uuid NOT NULL REFERENCES identity.users(id) ON DELETE RESTRICT,
    user_party_role varchar(40),
    title varchar(200) NOT NULL DEFAULT '새 사건',
    dispute_domain varchar(40),
    dispute_type varchar(80),
    status varchar(30) NOT NULL DEFAULT 'DRAFT'
        CHECK (status IN ('DRAFT','COLLECTING','REVIEW_READY','ANALYZING','READY','CLOSED')),
    user_goal text,
    original_statement text,
    occurred_at timestamptz,
    date_precision varchar(20)
        CHECK (date_precision IS NULL OR date_precision IN ('exact','day','month','unknown')),
    jurisdiction_code varchar(30) NOT NULL DEFAULT 'KR',
    risk_level varchar(20) NOT NULL DEFAULT 'normal'
        CHECK (risk_level IN ('normal','caution','urgent')),
    current_analysis_run_id uuid,
    confirmed_at timestamptz,
    version_no integer NOT NULL DEFAULT 1 CHECK (version_no > 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    deleted_at timestamptz,
    hard_delete_after timestamptz,
    retention_expires_at timestamptz NOT NULL DEFAULT (now() + interval '365 days'),
    retention_notice_sent_at timestamptz,
    UNIQUE (id, current_analysis_run_id),
    CHECK (status = 'DRAFT' OR original_statement IS NOT NULL),
    CHECK (deleted_at IS NULL OR hard_delete_after IS NOT NULL)
);

CREATE TABLE casework.case_parties (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    case_id uuid NOT NULL REFERENCES casework.cases(id) ON DELETE CASCADE,
    party_role varchar(40) NOT NULL,
    party_type varchar(20) NOT NULL CHECK (party_type IN ('person','company','agency')),
    display_name varchar(100) NOT NULL,
    relation_to_user varchar(100),
    contact_info_enc bytea,
    encryption_key_id varchar(100),
    source_type varchar(30) CHECK (source_type IS NULL OR source_type IN ('user_input','document','ai_inferred')),
    confirmation_status varchar(20) NOT NULL DEFAULT 'unconfirmed'
        CHECK (confirmation_status IN ('unconfirmed','confirmed','rejected')),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CHECK (contact_info_enc IS NULL OR encryption_key_id IS NOT NULL)
);

CREATE TABLE casework.files (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    case_id uuid NOT NULL REFERENCES casework.cases(id) ON DELETE CASCADE,
    uploaded_by uuid NOT NULL REFERENCES identity.users(id) ON DELETE RESTRICT,
    file_type varchar(40) NOT NULL,
    original_name varchar(255) NOT NULL,
    mime_type varchar(100) NOT NULL,
    size_bytes bigint NOT NULL CHECK (size_bytes > 0 AND size_bytes <= 20971520),
    storage_bucket varchar(100),
    object_key varchar(500) UNIQUE,
    sha256 char(64) NOT NULL,
    current_extraction_id uuid,
    current_ocr_revision_id uuid,
    lifecycle_status varchar(30) NOT NULL DEFAULT 'UPLOADING'
        CHECK (lifecycle_status IN ('UPLOADING','PROCESSING','REVIEW_REQUIRED','CONFIRMED','PURGE_PENDING','PURGED','FAILED')),
    malware_status varchar(20) NOT NULL DEFAULT 'pending'
        CHECK (malware_status IN ('pending','clean','blocked','failed')),
    malware_scan_provider varchar(50),
    malware_scanned_at timestamptz,
    rejection_reason varchar(100),
    pii_status varchar(20) NOT NULL DEFAULT 'unknown'
        CHECK (pii_status IN ('unknown','detected','reviewed','masked')),
    captured_at timestamptz,
    metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
    storage_expires_at timestamptz NOT NULL DEFAULT (now() + interval '24 hours'),
    purged_at timestamptz,
    purge_status varchar(20) NOT NULL DEFAULT 'scheduled'
        CHECK (purge_status IN ('scheduled','purged','retrying','failed')),
    purge_attempt_count integer NOT NULL DEFAULT 0,
    purge_next_retry_at timestamptz,
    purge_error_code varchar(80),
    created_at timestamptz NOT NULL DEFAULT now(),
    removed_at timestamptz,
    UNIQUE (id, current_extraction_id),
    UNIQUE (id, current_ocr_revision_id),
    CHECK (lifecycle_status <> 'PURGED' OR
           (storage_bucket IS NULL AND object_key IS NULL AND purged_at IS NOT NULL))
);

CREATE TABLE casework.case_statements (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    case_id uuid NOT NULL REFERENCES casework.cases(id) ON DELETE CASCADE,
    party_id uuid REFERENCES casework.case_parties(id) ON DELETE SET NULL,
    input_type varchar(30) NOT NULL
        CHECK (input_type IN ('initial','followup','message','manual')),
    raw_text text NOT NULL,
    normalized_text text,
    source_file_id uuid REFERENCES casework.files(id) ON DELETE SET NULL,
    occurred_at timestamptz,
    is_user_confirmed boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE casework.case_events (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    case_id uuid NOT NULL REFERENCES casework.cases(id) ON DELETE CASCADE,
    event_date date,
    event_time time,
    date_precision varchar(20) NOT NULL DEFAULT 'unknown'
        CHECK (date_precision IN ('exact','approximate','month','unknown')),
    event_type varchar(40) NOT NULL,
    title varchar(200) NOT NULL,
    description text,
    confidence numeric(5,4) CHECK (confidence BETWEEN 0 AND 1),
    confirmation_status varchar(20) NOT NULL DEFAULT 'unconfirmed'
        CHECK (confirmation_status IN ('unconfirmed','confirmed','disputed')),
    sort_order integer NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE casework.event_sources (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    event_id uuid NOT NULL REFERENCES casework.case_events(id) ON DELETE CASCADE,
    source_type varchar(30) NOT NULL
        CHECK (source_type IN ('statement','ocr_revision','answer','evidence')),
    source_id uuid NOT NULL,
    source_excerpt text,
    confidence numeric(5,4) CHECK (confidence BETWEEN 0 AND 1),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (event_id, source_type, source_id)
);

