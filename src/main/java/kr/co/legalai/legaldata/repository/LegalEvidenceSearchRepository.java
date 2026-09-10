package kr.co.legalai.legaldata.repository;

import kr.co.legalai.legaldata.dto.response.LegalEvidenceResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class LegalEvidenceSearchRepository {
    private final JdbcTemplate jdbc;

    public List<LegalEvidenceResponse> search(String query, float[] vector) {
        String literal = vectorLiteral(vector);
        // 양쪽 상위 20개 순위를 RRF(k=60)로 합친다. 점수는 법률적 신뢰도가 아니다.
        return jdbc.query("""
                WITH eligible AS MATERIALIZED (
                    SELECT c.id, c.document_id, c.content, c.heading_path,
                        d.title, d.source_url, d.version_label, d.effective_from,
                        e.embedding <=> ?::vector AS distance,
                        (SELECT count(DISTINCT term) FROM
                            regexp_split_to_table(lower(?), '[^[:alnum:]가-힣]+') AS term
                            WHERE length(term) >= 2 AND strpos(lower(c.content), term) > 0) AS hits
                    FROM knowledge.legal_chunks c
                    JOIN knowledge.legal_documents d ON d.id=c.document_id
                    JOIN knowledge.legal_sources s ON s.id=d.source_id
                    JOIN knowledge.chunk_embeddings e ON e.chunk_id=c.id
                    WHERE s.source_code='LAW_GO_KR_EFLAW' AND d.document_type='law' AND d.is_current
                      AND d.effective_from <= CURRENT_DATE
                      AND (d.effective_to IS NULL OR d.effective_to >= CURRENT_DATE)
                      AND d.source_url ~ '^https://www[.]law[.]go[.]kr/LSW/lsInfoP[.]do[?]'
                      AND c.chunk_type='article'
                      AND (c.metadata->'topic_tags') @> '["housing_lease"]'::jsonb
                      AND c.metadata->>'deleted'='false'
                      AND c.metadata->>'effective_from' <= to_char(CURRENT_DATE, 'YYYY-MM-DD')
                      AND e.embedding_model=? AND e.content_hash=c.metadata->>'content_hash'
                ), semantic AS (
                    SELECT id, row_number() OVER (ORDER BY distance, id) AS rank
                    FROM eligible ORDER BY distance, id LIMIT 20
                ), lexical AS (
                    SELECT id, row_number() OVER (ORDER BY hits DESC, id) AS rank
                    FROM eligible WHERE hits > 0 ORDER BY hits DESC, id LIMIT 20
                ), combined AS (
                    SELECT id, sum(score) AS score FROM (
                        SELECT id, 1.0/(60+rank) AS score FROM semantic
                        UNION ALL SELECT id, 1.0/(60+rank) AS score FROM lexical
                    ) ranks GROUP BY id
                )
                SELECT c.*, array_to_string(c.heading_path, ' > ') AS heading, r.score
                FROM combined r JOIN eligible c ON c.id=r.id
                ORDER BY r.score DESC, c.id LIMIT 8
                """, (rs, row) -> LegalEvidenceResponse.builder()
                .chunkId(rs.getObject("id", UUID.class)).documentId(rs.getObject("document_id", UUID.class))
                .title(rs.getString("title")).heading(rs.getString("heading")).content(rs.getString("content"))
                .sourceUrl(rs.getString("source_url")).versionLabel(rs.getString("version_label"))
                .effectiveFrom(rs.getObject("effective_from", LocalDate.class)).rankScore(rs.getDouble("score")).build(),
                literal, query, EmbeddingRepository.MODEL);
    }

    private String vectorLiteral(float[] vector) {
        if (vector == null || vector.length != 1536) throw new IllegalArgumentException("INVALID_SEARCH_VECTOR");
        StringBuilder result = new StringBuilder("[");
        double norm = 0;
        for (int i = 0; i < vector.length; i++) {
            if (!Float.isFinite(vector[i])) throw new IllegalArgumentException("INVALID_SEARCH_VECTOR");
            norm += (double) vector[i] * vector[i];
            if (i > 0) result.append(',');
            result.append(vector[i]);
        }
        if (norm == 0) throw new IllegalArgumentException("INVALID_SEARCH_VECTOR");
        return result.append(']').toString();
    }
}
