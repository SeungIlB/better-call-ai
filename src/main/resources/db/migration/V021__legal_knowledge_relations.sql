CREATE TABLE knowledge.legal_relations (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    source_chunk_id uuid NOT NULL REFERENCES knowledge.legal_chunks(id) ON DELETE CASCADE,
    target_chunk_id uuid NOT NULL REFERENCES knowledge.legal_chunks(id) ON DELETE CASCADE,
    relation_type varchar(40) NOT NULL CHECK (relation_type IN ('cites', 'related', 'exception', 'procedure')),
    confidence numeric(5,4) NOT NULL DEFAULT 1.0 CHECK (confidence >= 0 AND confidence <= 1),
    metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (source_chunk_id, target_chunk_id, relation_type),
    CHECK (source_chunk_id <> target_chunk_id)
);

CREATE INDEX legal_relations_source_idx ON knowledge.legal_relations(source_chunk_id, relation_type);
CREATE INDEX legal_relations_target_idx ON knowledge.legal_relations(target_chunk_id, relation_type);

COMMENT ON TABLE knowledge.legal_relations IS '법률 청크 사이의 인용·관련·예외·절차 관계 그래프';

GRANT SELECT ON knowledge.legal_relations TO legal_ai_app;
