CREATE TABLE casework.file_extractions (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    file_id uuid NOT NULL REFERENCES casework.files(id) ON DELETE CASCADE,
    model_run_id uuid,
    idempotency_key varchar(100) NOT NULL UNIQUE,
    attempt_count integer NOT NULL DEFAULT 1,
    extraction_type varchar(30) NOT NULL
        CHECK (extraction_type IN ('ocr','layout','vision','combined')),
    provider varchar(50) NOT NULL,
    model_name varchar(100) NOT NULL,
    status varchar(20) NOT NULL DEFAULT 'queued'
        CHECK (status IN ('queued','running','succeeded','failed')),
    raw_text text,
    layout_json jsonb,
    vision_json jsonb,
    overall_confidence numeric(5,4) CHECK (overall_confidence BETWEEN 0 AND 1),
    error_code varchar(80),
    error_message text,
    started_at timestamptz,
    completed_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (file_id, id)
);

CREATE TABLE casework.ocr_text_revisions (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    file_id uuid NOT NULL REFERENCES casework.files(id) ON DELETE CASCADE,
    extraction_id uuid NOT NULL REFERENCES casework.file_extractions(id) ON DELETE CASCADE,
    revision_no integer NOT NULL CHECK (revision_no > 0),
    corrected_text text NOT NULL,
    created_by uuid NOT NULL REFERENCES identity.users(id) ON DELETE RESTRICT,
    change_summary text,
    is_current boolean NOT NULL DEFAULT true,
    confirmed_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (extraction_id, revision_no),
    UNIQUE (file_id, id),
    UNIQUE (extraction_id, id)
);
CREATE UNIQUE INDEX uq_ocr_revision_current
    ON casework.ocr_text_revisions(extraction_id)
    WHERE is_current;

CREATE TABLE casework.extracted_fields (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    extraction_id uuid NOT NULL REFERENCES casework.file_extractions(id) ON DELETE CASCADE,
    ocr_revision_id uuid,
    case_id uuid NOT NULL REFERENCES casework.cases(id) ON DELETE CASCADE,
    field_group varchar(40) NOT NULL,
    field_name varchar(100) NOT NULL,
    raw_value text,
    normalized_value jsonb,
    page_no integer CHECK (page_no IS NULL OR page_no > 0),
    bounding_box jsonb,
    confidence numeric(5,4) CHECK (confidence BETWEEN 0 AND 1),
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE casework.pii_findings (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    case_id uuid NOT NULL REFERENCES casework.cases(id) ON DELETE CASCADE,
    source_type varchar(30) NOT NULL
        CHECK (source_type IN ('statement','ocr_revision','generated_document')),
    source_id uuid NOT NULL,
    source_content_hash char(64) NOT NULL,
    pii_type varchar(40) NOT NULL,
    text_start integer,
    text_end integer,
    detected_text_hash char(64),
    mask_status varchar(20) NOT NULL DEFAULT 'detected'
        CHECK (mask_status IN ('detected','accepted','masked','dismissed')),
    masked_by uuid REFERENCES identity.users(id) ON DELETE SET NULL,
    masked_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    CHECK (text_start IS NULL OR text_start >= 0),
    CHECK (text_end IS NULL OR text_end >= text_start)
);

CREATE TABLE casework.fact_conflicts (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    case_id uuid NOT NULL REFERENCES casework.cases(id) ON DELETE CASCADE,
    field_name varchar(100) NOT NULL,
    left_source_type varchar(30) NOT NULL,
    left_source_id uuid NOT NULL,
    left_value jsonb NOT NULL,
    right_source_type varchar(30) NOT NULL,
    right_source_id uuid NOT NULL,
    right_value jsonb NOT NULL,
    status varchar(30) NOT NULL DEFAULT 'OPEN'
        CHECK (status IN ('OPEN','RESOLVED','ACCEPTED_AS_UNCERTAIN')),
    resolved_value jsonb,
    resolved_by uuid REFERENCES identity.users(id) ON DELETE SET NULL,
    resolved_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE casework.case_claims (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    case_id uuid NOT NULL REFERENCES casework.cases(id) ON DELETE CASCADE,
    claim_code varchar(100) NOT NULL,
    claim_type varchar(40) NOT NULL,
    title varchar(200) NOT NULL,
    description text,
    status varchar(20) NOT NULL DEFAULT 'PROPOSED'
        CHECK (status IN ('PROPOSED','CONFIRMED','DISPUTED','REJECTED')),
    created_by_type varchar(20) NOT NULL CHECK (created_by_type IN ('user','ai')),
    confirmed_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (case_id, claim_code)
);

CREATE TABLE casework.evidence_items (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    case_id uuid NOT NULL REFERENCES casework.cases(id) ON DELETE CASCADE,
    file_id uuid REFERENCES casework.files(id) ON DELETE SET NULL,
    statement_id uuid REFERENCES casework.case_statements(id) ON DELETE SET NULL,
    extracted_field_id uuid REFERENCES casework.extracted_fields(id) ON DELETE SET NULL,
    evidence_type varchar(40) NOT NULL,
    title varchar(200) NOT NULL,
    description text,
    status varchar(30) NOT NULL CHECK (status IN ('secured','needs_more','caution')),
    supports_side varchar(20) CHECK (supports_side IS NULL OR supports_side IN ('user','counterpart','neutral')),
    relevance_score numeric(5,4) CHECK (relevance_score BETWEEN 0 AND 1),
    is_user_confirmed boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CHECK (num_nonnulls(file_id, statement_id, extracted_field_id) >= 1)
);

CREATE TABLE casework.evidence_claim_links (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    evidence_id uuid NOT NULL REFERENCES casework.evidence_items(id) ON DELETE CASCADE,
    claim_id uuid NOT NULL REFERENCES casework.case_claims(id) ON DELETE CASCADE,
    link_type varchar(20) NOT NULL CHECK (link_type IN ('SUPPORTS','CONTRADICTS','CONTEXTUAL')),
    relevance_score numeric(5,4) CHECK (relevance_score BETWEEN 0 AND 1),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (evidence_id, claim_id, link_type)
);

CREATE TABLE casework.case_questions (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    case_id uuid NOT NULL REFERENCES casework.cases(id) ON DELETE CASCADE,
    model_run_id uuid,
    question_code varchar(100),
    question_text text NOT NULL,
    reason text NOT NULL,
    answer_type varchar(30) NOT NULL CHECK (answer_type IN ('text','choice','date','boolean','file')),
    options jsonb,
    priority smallint NOT NULL DEFAULT 0,
    sequence_no integer NOT NULL,
    status varchar(20) NOT NULL DEFAULT 'pending'
        CHECK (status IN ('pending','answered','skipped','obsolete')),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (case_id, sequence_no)
);

CREATE TABLE casework.case_answers (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    question_id uuid NOT NULL UNIQUE REFERENCES casework.case_questions(id) ON DELETE CASCADE,
    case_id uuid NOT NULL REFERENCES casework.cases(id) ON DELETE CASCADE,
    user_id uuid NOT NULL REFERENCES identity.users(id) ON DELETE RESTRICT,
    answer_text text,
    answer_value jsonb,
    is_skipped boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CHECK (is_skipped OR answer_text IS NOT NULL OR answer_value IS NOT NULL)
);

