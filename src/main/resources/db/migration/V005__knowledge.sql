CREATE TABLE knowledge.legal_sources (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    source_code varchar(50) NOT NULL UNIQUE,
    source_type varchar(30) NOT NULL CHECK (source_type IN ('law','case','mediation','guide','form')),
    publisher varchar(200) NOT NULL,
    base_url text,
    authority_level smallint NOT NULL CHECK (authority_level BETWEEN 1 AND 10),
    license_info text,
    sync_method varchar(30) CHECK (sync_method IN ('api','crawl','manual')),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE knowledge.legal_documents (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    source_id uuid NOT NULL REFERENCES knowledge.legal_sources(id) ON DELETE RESTRICT,
    external_id varchar(255) NOT NULL,
    document_type varchar(30) NOT NULL,
    title text NOT NULL,
    jurisdiction_code varchar(30) NOT NULL DEFAULT 'KR',
    case_number varchar(100),
    court_name varchar(200),
    decision_date date,
    effective_from date,
    effective_to date,
    version_label varchar(60) NOT NULL,
    source_url text NOT NULL,
    raw_text text NOT NULL,
    normalized_text text,
    content_hash char(64) NOT NULL,
    is_current boolean NOT NULL DEFAULT true,
    fetched_at timestamptz NOT NULL DEFAULT now(),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (source_id, external_id, version_label)
);

CREATE TABLE knowledge.legal_chunks (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    document_id uuid NOT NULL REFERENCES knowledge.legal_documents(id) ON DELETE CASCADE,
    parent_chunk_id uuid REFERENCES knowledge.legal_chunks(id) ON DELETE SET NULL,
    chunk_type varchar(40) NOT NULL,
    heading_path text[],
    content text NOT NULL,
    ordinal integer NOT NULL CHECK (ordinal >= 0),
    token_count integer CHECK (token_count IS NULL OR token_count >= 0),
    search_vector tsvector GENERATED ALWAYS AS
        (to_tsvector('simple', coalesce(content,''))) STORED,
    metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (document_id, ordinal)
);

CREATE TABLE knowledge.chunk_embeddings (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    chunk_id uuid NOT NULL REFERENCES knowledge.legal_chunks(id) ON DELETE CASCADE,
    embedding_model varchar(100) NOT NULL,
    dimensions integer NOT NULL DEFAULT 1536 CHECK (dimensions = 1536),
    embedding vector(1536) NOT NULL,
    content_hash char(64) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (chunk_id, embedding_model, content_hash)
);

