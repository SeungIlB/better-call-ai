package kr.co.legalai.legaldata.repository;

import kr.co.legalai.legaldata.entity.CollectedLaw;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 쓰기 호출은 운영 명령의 migration 연결 + 트랜잭션에서만 수행한다. 앱 DB 역할은 계속 읽기 전용이다. */
@Repository
@RequiredArgsConstructor
public class LawImportRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public boolean save(CollectedLaw law) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 19))", rs -> { }, law.externalId());
        jdbc.update("""
                INSERT INTO knowledge.legal_sources(source_code, source_type, publisher, base_url, authority_level, sync_method)
                VALUES ('LAW_GO_KR_EFLAW', 'law', '법제처 국가법령정보센터', 'https://www.law.go.kr', 1, 'api')
                ON CONFLICT (source_code) DO NOTHING
                """);
        UUID source = jdbc.queryForObject("SELECT id FROM knowledge.legal_sources WHERE source_code='LAW_GO_KR_EFLAW'", UUID.class);
        var existing = jdbc.queryForList("""
                SELECT id, content_hash FROM knowledge.legal_documents
                WHERE source_id=? AND external_id=? AND version_label=?
                """, source, law.externalId(), law.versionLabel());
        if (!existing.isEmpty()) {
            if (!law.contentHash().equals(existing.getFirst().get("content_hash"))) {
                throw new IllegalStateException("LAW_SAME_VERSION_CONTENT_CHANGED");
            }
            int count = jdbc.queryForObject("SELECT count(*) FROM knowledge.legal_chunks WHERE document_id=?", Integer.class,
                    existing.getFirst().get("id"));
            if (count != law.parts().size()) throw new IllegalStateException("LAW_CHUNK_COUNT_MISMATCH");
            return false;
        }
        UUID document = UUID.randomUUID();
        // 현행 여부는 동일 공식 ID의 수집본에 대해서만 갱신한다. effective_to는 추측하여 채우지 않는다.
        jdbc.update("UPDATE knowledge.legal_documents SET is_current=false WHERE source_id=? AND external_id=?",
                source, law.externalId());
        jdbc.update("""
                INSERT INTO knowledge.legal_documents(id, source_id, external_id, document_type, title, version_label,
                    source_url, raw_text, normalized_text, content_hash, effective_from, law_kind)
                VALUES (?, ?, ?, 'law', ?, ?, ?, ?, ?, ?, ?, ?)
                """, document, source, law.externalId(), law.title(), law.versionLabel(), law.sourceUrl(), law.rawJson(),
                String.join("\n\n", law.parts().stream().map(CollectedLaw.Part::content).toList()),
                law.contentHash(), law.effectiveFrom(), law.kind().name());
        List<Object[]> rows = new java.util.ArrayList<>();
        for (int i = 0; i < law.parts().size(); i++) {
            var part = law.parts().get(i);
            rows.add(new Object[]{document, part.type(), part.heading(), part.content(), i, mapper.writeValueAsString(part.metadata())});
        }
        jdbc.batchUpdate("""
                INSERT INTO knowledge.legal_chunks(document_id, chunk_type, heading_path, content, ordinal, metadata)
                VALUES (?, ?, ARRAY[?]::text[], ?, ?, ?::jsonb)
                """, rows);
        return true;
    }

    public List<EmbeddingInput> pendingEmbeddings(String model, int limit) {
        return jdbc.query("""
                SELECT c.id, c.content, c.metadata->>'content_hash' AS content_hash
                FROM knowledge.legal_chunks c
                JOIN knowledge.legal_documents d ON d.id=c.document_id
                JOIN knowledge.legal_sources s ON s.id=d.source_id
                WHERE s.source_code='LAW_GO_KR_EFLAW' AND d.is_current
                  AND (c.metadata->'topic_tags') @> '["housing_lease"]'::jsonb
                  AND NOT EXISTS (SELECT 1 FROM knowledge.chunk_embeddings e WHERE e.chunk_id=c.id
                    AND e.embedding_model=? AND e.content_hash=c.metadata->>'content_hash')
                ORDER BY d.external_id, c.ordinal LIMIT ?
                """, (rs, row) -> new EmbeddingInput(rs.getObject("id", UUID.class), rs.getString("content"),
                rs.getString("content_hash")), model, limit);
    }

    public void saveEmbeddings(String model, List<EmbeddingInput> inputs, List<float[]> vectors) {
        if (inputs.size() != vectors.size()) throw new IllegalArgumentException("EMBEDDING_COUNT_MISMATCH");
        List<Object[]> rows = new java.util.ArrayList<>();
        for (int i = 0; i < inputs.size(); i++) {
            rows.add(new Object[]{inputs.get(i).id(), model, vectorLiteral(vectors.get(i)), inputs.get(i).hash()});
        }
        jdbc.batchUpdate("""
                INSERT INTO knowledge.chunk_embeddings(chunk_id, embedding_model, dimensions, embedding, content_hash)
                VALUES (?, ?, 1536, ?::vector, ?) ON CONFLICT (chunk_id, embedding_model, content_hash) DO NOTHING
                """, rows);
    }

    public List<Map<String, Object>> summary() {
        return jdbc.queryForList("""
                SELECT d.title, d.version_label, d.law_kind, d.effective_from, d.is_current,
                    count(DISTINCT c.id) AS chunks,
                    count(DISTINCT c.id) FILTER (WHERE (c.metadata->'topic_tags') @> '["housing_lease"]') AS selected_chunks,
                    count(DISTINCT e.chunk_id) AS embedded_chunks
                FROM knowledge.legal_documents d JOIN knowledge.legal_sources s ON s.id=d.source_id
                LEFT JOIN knowledge.legal_chunks c ON c.document_id=d.id
                LEFT JOIN knowledge.chunk_embeddings e ON e.chunk_id=c.id AND e.embedding_model='text-embedding-3-small'
                  AND e.content_hash=c.metadata->>'content_hash'
                WHERE s.source_code='LAW_GO_KR_EFLAW'
                GROUP BY d.id ORDER BY d.title, d.effective_from
                """);
    }

    public List<Map<String, Object>> search(float[] vector) {
        return jdbc.queryForList("""
                SELECT d.title, array_to_string(c.heading_path, ' > ') AS heading, d.source_url,
                    1 - (e.embedding <=> ?::vector) AS similarity
                FROM knowledge.chunk_embeddings e JOIN knowledge.legal_chunks c ON c.id=e.chunk_id
                JOIN knowledge.legal_documents d ON d.id=c.document_id
                JOIN knowledge.legal_sources s ON s.id=d.source_id
                WHERE s.source_code='LAW_GO_KR_EFLAW' AND d.is_current
                  AND e.embedding_model='text-embedding-3-small' AND e.content_hash=c.metadata->>'content_hash'
                  AND (c.metadata->'topic_tags') @> '["housing_lease"]'
                ORDER BY e.embedding <=> ?::vector LIMIT 5
                """, vectorLiteral(vector), vectorLiteral(vector));
    }

    private String vectorLiteral(float[] vector) {
        if (vector.length != 1536) throw new IllegalArgumentException("EMBEDDING_DIMENSION_MISMATCH");
        StringBuilder result = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            if (!Float.isFinite(vector[i])) throw new IllegalArgumentException("INVALID_EMBEDDING_VALUE");
            if (i > 0) result.append(',');
            result.append(vector[i]);
        }
        return result.append(']').toString();
    }

    public record EmbeddingInput(UUID id, String content, String hash) { }
}
