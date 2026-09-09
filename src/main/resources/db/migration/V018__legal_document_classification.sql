ALTER TABLE knowledge.legal_documents ADD COLUMN law_kind varchar(40);
ALTER TABLE knowledge.legal_documents ADD CONSTRAINT ck_legal_law_kind CHECK (
    law_kind IS NULL OR (document_type = 'law' AND law_kind IN (
        'CONSTITUTION', 'ACT', 'PRESIDENTIAL_DECREE',
        'PRIME_MINISTER_ORDINANCE', 'MINISTERIAL_ORDINANCE', 'RULE'
    ))
);
COMMENT ON COLUMN knowledge.legal_documents.law_kind IS
    '공식 법령 형식. 헌법/법률/대통령령/총리령/부령/규칙. 법령명·주제와 구분하며 미확인 및 비법령 문서는 NULL';

-- 주제는 법령 전체가 아니라 개별 조문·문서 조각에 부여한다. 기존 metadata를 재사용한다.
ALTER TABLE knowledge.legal_chunks ADD CONSTRAINT ck_chunk_topic_tags CHECK (
    NOT (metadata ? 'topic_tags') OR (
        jsonb_typeof(metadata -> 'topic_tags') = 'array'
        AND NOT jsonb_path_exists(metadata,
            '$.topic_tags[*] ? (@.type() != "string" || @ like_regex "^\\s*$")')
    )
);
COMMENT ON COLUMN knowledge.legal_chunks.metadata IS
    '조각별 메타데이터. topic_tags는 비어 있지 않은 문자열 배열(예: housing_lease, repair_duty). 미분류는 키 생략 또는 빈 배열. 법령 전체에 일괄 전파하지 않음';
CREATE INDEX ix_legal_documents_kind ON knowledge.legal_documents(law_kind) WHERE law_kind IS NOT NULL;
CREATE INDEX ix_legal_chunks_topics ON knowledge.legal_chunks USING gin ((metadata -> 'topic_tags'));
