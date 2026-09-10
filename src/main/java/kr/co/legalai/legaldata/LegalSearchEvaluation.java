package kr.co.legalai.legaldata;

import kr.co.legalai.legaldata.repository.EmbeddingRepository;
import kr.co.legalai.legaldata.repository.LegalEvidenceSearchRepository;
import kr.co.legalai.legaldata.service.impl.HousingSearchTerms;
import kr.co.legalai.legaldata.service.impl.LawArticleParser;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 명시적인 운영 명령으로만 실행하는 고정 가상 질문 평가. */
public final class LegalSearchEvaluation {
    private LegalSearchEvaluation() { }
    private record Example(String id, String query, List<String> targets) { }
    private static final List<Example> EXAMPLES = List.of(
            new Example("repair-paid", "임대인이 집 수리를 해주지 않아 제가 수리비를 냈습니다. 돌려받을 수 있나요?", List.of("민법 제623조", "민법 제626조")),
            new Example("boiler", "전셋집 보일러가 고장 났는데 집주인이 고쳐주지 않아요.", List.of("민법 제623조")),
            new Example("mold", "월세 집 벽에 곰팡이가 심한데 집주인에게 보수를 요청하고 싶어요.", List.of("민법 제623조")),
            new Example("leak-paid", "임차한 주택에 누수가 생겨 제가 수리 비용을 지출했습니다. 상환이 가능한지 확인하고 싶습니다.", List.of("민법 제626조")),
            new Example("room-unusable", "월세 집 방 하나에 물이 새서 쓸 수 없어요. 월세를 줄일 수 있나요?", List.of("민법 제627조")),
            new Example("deposit-move", "전세 계약이 끝났는데 보증금을 못 받았어요. 이사를 앞두고 있습니다.", List.of("주택임대차보호법 제3조의3")),
            new Example("renewed-exit", "월세 계약이 자동 연장됐는데 이제 나가고 싶어요. 집주인에게 언제 말해야 하나요?", List.of("주택임대차보호법 제6조의2")),
            new Example("repair-legal", "임대인의 목적물 사용 수익에 필요한 상태 유지 의무를 확인하고 싶습니다.", List.of("민법 제623조")),
            new Example("expense-legal", "주택 임차인이 지출한 필요비 상환청구권에 관한 근거를 찾아주세요.", List.of("민법 제626조")),
            new Example("renewal-legal", "주택임대차의 묵시적 갱신 이후 임차인의 해지 통지에 관한 조문을 찾아주세요.", List.of("주택임대차보호법 제6조의2")),
            new Example("holdout-ceiling", "살고 있는 월셋집 천장에서 물이 샙니다. 주인이 해결해야 하는지 알고 싶어요.", List.of("민법 제623조")),
            new Example("holdout-plumbing", "전세 주택 배관 고장으로 수선 업체에 돈을 지불했어요. 주인에게 비용을 요청하려 합니다.", List.of("민법 제626조")),
            new Example("holdout-deposit", "집 계약이 끝났고 보증금을 돌려주지 않아 다른 곳으로 옮기기 어렵습니다.", List.of("주택임대차보호법 제3조의3")),
            new Example("holdout-renewal", "월세 계약이 묵시적으로 갱신된 뒤 종료하고 싶어 임대인에게 연락하려고 합니다.", List.of("주택임대차보호법 제6조의2")));

    public static void run(JdbcTemplate jdbc, EmbeddingRepository embeddings, ObjectMapper mapper) throws Exception {
        Path root = Path.of("build", "legal-search-evaluation", Long.toString(System.currentTimeMillis()));
        Files.createDirectories(root);
        // 정답은 호출 전에 기록한다. 검색 결과를 보고 목표 조문을 바꾸지 않는다.
        mapper.writerWithDefaultPrettyPrinter().writeValue(root.resolve("examples.json").toFile(), EXAMPLES);
        var corpus = jdbc.queryForList("""
                SELECT d.external_id, d.version_label, c.id, e.content_hash
                FROM knowledge.legal_documents d JOIN knowledge.legal_chunks c ON c.document_id=d.id
                JOIN knowledge.chunk_embeddings e ON e.chunk_id=c.id
                WHERE d.is_current AND e.embedding_model=? ORDER BY d.external_id, c.ordinal, e.id
                """, EmbeddingRepository.MODEL);
        var search = new LegalEvidenceSearchRepository(jdbc);
        String rulesHash = LawArticleParser.hash(Files.readString(Path.of(
                "src/main/java/kr/co/legalai/legaldata/service/impl/HousingSearchTerms.java")));
        var rows = new ArrayList<Map<String, Object>>();
        int baseline = 0, expanded = 0, total = 0;
        for (Example example : EXAMPLES) {
            String enhanced = HousingSearchTerms.expand(example.query());
            var vectors = embeddings.embed(List.of(example.query(), enhanced));
            var before = search.search(example.query(), vectors.get(0)).stream().map(r -> r.heading()).distinct().toList();
            var after = search.search(enhanced, vectors.get(1)).stream().map(r -> r.heading()).distinct().toList();
            int oldHits = (int) example.targets().stream().filter(before::contains).count();
            int newHits = (int) example.targets().stream().filter(after::contains).count();
            baseline += oldHits; expanded += newHits; total += example.targets().size();
            rows.add(Map.of("id", example.id(), "cohort", example.id().startsWith("holdout-") ? "holdout" : "development",
                    "expandedQuery", enhanced, "targets", example.targets(),
                    "before", before, "after", after, "baselineHits", oldHits, "expandedHits", newHits));
            System.out.println(example.id() + " baseline=" + oldHits + " expanded=" + newHits + " targets=" + example.targets().size());
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("model", EmbeddingRepository.MODEL);
            report.put("rulesHash", rulesHash);
            report.put("corpusHash", LawArticleParser.hash(mapper.writeValueAsString(corpus)));
            report.put("completed", rows.size() == EXAMPLES.size());
            report.put("baselineHits", baseline); report.put("expandedHits", expanded); report.put("totalTargets", total);
            report.put("rows", rows);
            mapper.writerWithDefaultPrettyPrinter().writeValue(root.resolve("report.json").toFile(), report);
        }
        System.out.println("SEARCH_EVALUATION baseline=" + baseline + "/" + total + " expanded=" + expanded + "/" + total);
        System.out.println("REPORT " + root.resolve("report.json"));
    }
}
