CREATE TABLE aiops.prompt_versions (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    prompt_key varchar(100) NOT NULL,
    version_no integer NOT NULL CHECK (version_no > 0),
    system_prompt text NOT NULL,
    output_schema jsonb NOT NULL,
    model_policy jsonb NOT NULL DEFAULT '{}'::jsonb,
    is_active boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (prompt_key, version_no)
);
CREATE UNIQUE INDEX uq_prompt_active
    ON aiops.prompt_versions(prompt_key) WHERE is_active;

CREATE TABLE aiops.model_runs (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    case_id uuid REFERENCES casework.cases(id) ON DELETE CASCADE,
    task_type varchar(50) NOT NULL,
    idempotency_key varchar(100) NOT NULL UNIQUE,
    prompt_version_id uuid REFERENCES aiops.prompt_versions(id) ON DELETE RESTRICT,
    provider varchar(50) NOT NULL,
    model_name varchar(100) NOT NULL,
    status varchar(20) NOT NULL DEFAULT 'queued'
        CHECK (status IN ('queued','running','succeeded','failed')),
    input_payload_enc bytea,
    encryption_key_id varchar(100),
    output_payload_enc bytea,
    payload_expires_at timestamptz NOT NULL DEFAULT (now() + interval '7 days'),
    input_hash char(64) NOT NULL,
    input_tokens integer CHECK (input_tokens IS NULL OR input_tokens >= 0),
    output_tokens integer CHECK (output_tokens IS NULL OR output_tokens >= 0),
    latency_ms integer CHECK (latency_ms IS NULL OR latency_ms >= 0),
    estimated_cost numeric(18,6) CHECK (estimated_cost IS NULL OR estimated_cost >= 0),
    trace_id varchar(100),
    error_code varchar(80),
    error_message text,
    started_at timestamptz,
    completed_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    CHECK ((input_payload_enc IS NULL AND output_payload_enc IS NULL) OR encryption_key_id IS NOT NULL)
);

CREATE TABLE aiops.analysis_runs (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    case_id uuid NOT NULL REFERENCES casework.cases(id) ON DELETE CASCADE,
    trigger_type varchar(30) NOT NULL
        CHECK (trigger_type IN ('initial','user_edit','new_evidence','manual')),
    version_no integer NOT NULL CHECK (version_no > 0),
    idempotency_key varchar(100) NOT NULL UNIQUE,
    input_fingerprint char(64) NOT NULL,
    display_snapshot jsonb,
    status varchar(20) NOT NULL DEFAULT 'queued'
        CHECK (status IN ('queued','running','succeeded','failed')),
    stale_at timestamptz,
    stale_reason varchar(60),
    superseded_by_run_id uuid REFERENCES aiops.analysis_runs(id) ON DELETE SET NULL,
    summary text,
    responsibility_result jsonb,
    confidence_level varchar(20)
        CHECK (confidence_level IS NULL OR confidence_level IN ('high','medium','low')),
    confidence_reason text,
    uncertainty jsonb,
    expert_referral boolean NOT NULL DEFAULT false,
    referral_reason text,
    started_at timestamptz NOT NULL DEFAULT now(),
    completed_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (case_id, version_no),
    UNIQUE (case_id, id)
);
CREATE UNIQUE INDEX uq_analysis_running_per_case
    ON aiops.analysis_runs(case_id)
    WHERE status IN ('queued','running');

CREATE TABLE aiops.analysis_run_inputs (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    analysis_run_id uuid NOT NULL REFERENCES aiops.analysis_runs(id) ON DELETE CASCADE,
    source_type varchar(30) NOT NULL
        CHECK (source_type IN ('statement','ocr_revision','answer','event','evidence')),
    source_id uuid NOT NULL,
    source_version integer,
    content_hash char(64) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE NULLS NOT DISTINCT
        (analysis_run_id, source_type, source_id, source_version)
);

CREATE TABLE aiops.retrieval_runs (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    case_id uuid NOT NULL REFERENCES casework.cases(id) ON DELETE CASCADE,
    analysis_run_id uuid REFERENCES aiops.analysis_runs(id) ON DELETE CASCADE,
    query_text text NOT NULL,
    query_embedding_model varchar(100),
    filters jsonb NOT NULL DEFAULT '{}'::jsonb,
    keyword_weight numeric(5,4) CHECK (keyword_weight BETWEEN 0 AND 1),
    vector_weight numeric(5,4) CHECK (vector_weight BETWEEN 0 AND 1),
    top_k integer NOT NULL CHECK (top_k > 0),
    status varchar(20) NOT NULL DEFAULT 'running'
        CHECK (status IN ('running','succeeded','failed')),
    started_at timestamptz NOT NULL DEFAULT now(),
    completed_at timestamptz,
    CHECK (keyword_weight IS NULL OR vector_weight IS NULL OR
           keyword_weight + vector_weight = 1)
);

CREATE TABLE aiops.retrieval_results (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    retrieval_run_id uuid NOT NULL REFERENCES aiops.retrieval_runs(id) ON DELETE CASCADE,
    chunk_id uuid NOT NULL REFERENCES knowledge.legal_chunks(id) ON DELETE RESTRICT,
    initial_rank integer NOT NULL CHECK (initial_rank > 0),
    keyword_score numeric(10,6),
    vector_score numeric(10,6),
    rerank_score numeric(10,6),
    final_rank integer CHECK (final_rank IS NULL OR final_rank > 0),
    is_selected boolean NOT NULL DEFAULT false,
    selection_reason text,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (retrieval_run_id, chunk_id)
);

CREATE TABLE aiops.analysis_findings (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    analysis_run_id uuid NOT NULL REFERENCES aiops.analysis_runs(id) ON DELETE CASCADE,
    finding_type varchar(40) NOT NULL
        CHECK (finding_type IN ('favorable','unfavorable','counterargument','variable','warning')),
    side varchar(20) CHECK (side IS NULL OR side IN ('user','counterpart','neutral')),
    title varchar(200) NOT NULL,
    content text NOT NULL,
    confidence numeric(5,4) CHECK (confidence BETWEEN 0 AND 1),
    sort_order integer NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE aiops.analysis_finding_sources (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    finding_id uuid NOT NULL REFERENCES aiops.analysis_findings(id) ON DELETE CASCADE,
    source_type varchar(30) NOT NULL
        CHECK (source_type IN ('statement','ocr_revision','answer','event','evidence')),
    source_id uuid NOT NULL,
    support_type varchar(20) NOT NULL
        CHECK (support_type IN ('supports','contradicts','contextual')),
    relevance_score numeric(5,4) CHECK (relevance_score BETWEEN 0 AND 1),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (finding_id, source_type, source_id, support_type)
);

CREATE TABLE aiops.analysis_citations (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    finding_id uuid NOT NULL REFERENCES aiops.analysis_findings(id) ON DELETE CASCADE,
    chunk_id uuid NOT NULL REFERENCES knowledge.legal_chunks(id) ON DELETE RESTRICT,
    quoted_text text NOT NULL,
    claim_text text NOT NULL,
    entailment_status varchar(20) NOT NULL
        CHECK (entailment_status IN ('supported','partial','conflict','rejected')),
    verification_score numeric(5,4) CHECK (verification_score BETWEEN 0 AND 1),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (finding_id, chunk_id, claim_text)
);

