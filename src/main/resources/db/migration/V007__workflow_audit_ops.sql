CREATE TABLE workflow.action_plans (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    case_id uuid NOT NULL REFERENCES casework.cases(id) ON DELETE CASCADE,
    analysis_run_id uuid NOT NULL REFERENCES aiops.analysis_runs(id) ON DELETE RESTRICT,
    version_no integer NOT NULL CHECK (version_no > 0),
    summary text,
    status varchar(20) NOT NULL DEFAULT 'active'
        CHECK (status IN ('active','completed','superseded')),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (case_id, version_no)
);

CREATE TABLE workflow.action_items (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    action_plan_id uuid NOT NULL REFERENCES workflow.action_plans(id) ON DELETE CASCADE,
    stage varchar(20) NOT NULL CHECK (stage IN ('now','soon','later')),
    action_type varchar(40) NOT NULL,
    title varchar(200) NOT NULL,
    description text,
    priority smallint NOT NULL,
    due_at timestamptz,
    status varchar(20) NOT NULL DEFAULT 'pending'
        CHECK (status IN ('pending','in_progress','completed','skipped')),
    completed_at timestamptz,
    user_note text,
    sort_order integer NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE workflow.generated_documents (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    document_group_id uuid NOT NULL,
    previous_version_id uuid REFERENCES workflow.generated_documents(id) ON DELETE SET NULL,
    case_id uuid NOT NULL REFERENCES casework.cases(id) ON DELETE CASCADE,
    analysis_run_id uuid REFERENCES aiops.analysis_runs(id) ON DELETE RESTRICT,
    model_run_id uuid REFERENCES aiops.model_runs(id) ON DELETE SET NULL,
    document_type varchar(40) NOT NULL
        CHECK (document_type IN ('message','certified_notice','statement')),
    title varchar(200) NOT NULL,
    content_enc bytea NOT NULL,
    encryption_key_id varchar(100) NOT NULL,
    content_hash char(64) NOT NULL,
    format varchar(20) NOT NULL CHECK (format IN ('text','markdown','html')),
    tone varchar(20) CHECK (tone IS NULL OR tone IN ('polite','firm','settlement')),
    version_no integer NOT NULL CHECK (version_no > 0),
    status varchar(20) NOT NULL DEFAULT 'draft'
        CHECK (status IN ('draft','reviewed','exported','superseded')),
    is_user_edited boolean NOT NULL DEFAULT false,
    reviewed_at timestamptz,
    pii_confirmed_at timestamptz,
    exported_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (document_group_id, version_no)
);

CREATE TABLE workflow.document_participant_snapshots (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    generated_document_id uuid NOT NULL REFERENCES workflow.generated_documents(id) ON DELETE CASCADE,
    participant_role varchar(20) NOT NULL CHECK (participant_role IN ('SENDER','RECIPIENT')),
    party_type varchar(20) NOT NULL CHECK (party_type IN ('person','company','agency')),
    name_enc bytea NOT NULL,
    address_enc bytea NOT NULL,
    contact_enc bytea,
    encryption_key_id varchar(100) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (generated_document_id, participant_role)
);

CREATE TABLE workflow.generated_document_sources (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    generated_document_id uuid NOT NULL REFERENCES workflow.generated_documents(id) ON DELETE CASCADE,
    source_type varchar(30) NOT NULL
        CHECK (source_type IN ('event','evidence','answer','citation')),
    source_id uuid NOT NULL,
    usage_purpose varchar(50),
    content_span jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (generated_document_id, source_type, source_id, usage_purpose)
);

CREATE TABLE audit.audit_logs (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    actor_user_id uuid REFERENCES identity.users(id) ON DELETE SET NULL,
    case_id uuid REFERENCES casework.cases(id) ON DELETE SET NULL,
    action varchar(60) NOT NULL,
    entity_type varchar(60) NOT NULL,
    entity_id uuid,
    before_data jsonb,
    after_data jsonb,
    trace_id varchar(100),
    ip_hash char(64),
    user_agent text,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE ops.outbox_events (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    event_type varchar(50) NOT NULL,
    aggregate_type varchar(40) NOT NULL,
    aggregate_id uuid NOT NULL,
    payload jsonb NOT NULL DEFAULT '{}'::jsonb,
    status varchar(20) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING','PROCESSING','SUCCEEDED','RETRY_WAIT','DEAD')),
    attempt_count integer NOT NULL DEFAULT 0,
    next_retry_at timestamptz,
    locked_at timestamptz,
    locked_by varchar(100),
    lease_expires_at timestamptz,
    idempotency_key varchar(120) NOT NULL UNIQUE,
    last_error_code varchar(80),
    created_at timestamptz NOT NULL DEFAULT now(),
    processed_at timestamptz,
    retention_expires_at timestamptz NOT NULL,
    CHECK (jsonb_typeof(payload) = 'object')
);

