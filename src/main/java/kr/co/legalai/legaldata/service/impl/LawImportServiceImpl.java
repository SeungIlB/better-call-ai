package kr.co.legalai.legaldata.service.impl;

import kr.co.legalai.legaldata.repository.EmbeddingRepository;
import kr.co.legalai.legaldata.repository.LawImportRepository;
import kr.co.legalai.legaldata.repository.LawOpenDataRepository;
import kr.co.legalai.legaldata.service.LawImportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class LawImportServiceImpl implements LawImportService {
    private final LawOpenDataRepository source;
    private final LawArticleParser parser;
    private final LawImportRepository repository;
    private final EmbeddingRepository embeddings;
    private final TransactionTemplate transactions;

    @Override
    public void collect() {
        collect(List.of("민법", "주택임대차보호법", "주택임대차보호법 시행령"), "housing_lease");
    }

    @Override
    public void collectVehicle() {
        collect(List.of("민법", "도로교통법", "교통사고처리 특례법", "자동차손해배상 보장법"), "vehicle_accident");
    }

    @Override
    public void collectAssault() {
        collect(List.of("형법", "민법"), "assault");
    }

    @Override
    public void collectLabor() {
        collect(List.of("근로기준법", "근로기준법 시행령", "근로기준법 시행규칙"), "labor");
    }

    private void collect(List<String> titles, String domain) {
        for (String title : titles) {
            var matches = LawArticleParser.nodes(source.searchCurrentLaws(title).path("LawSearch").path("law"))
                    .stream().filter(row -> title.equals(row.path("법령명한글").asString())).toList();
            if (matches.size() != 1) throw new IllegalStateException("LAW_EXACT_MATCH_REQUIRED");
            var item = matches.getFirst();
            var law = parser.parse(title, item, source.findLawVersion(LawArticleParser.required(item, "법령일련번호"),
                    LawArticleParser.required(item, "시행일자")), domain);
            boolean inserted = Boolean.TRUE.equals(transactions.execute(status -> repository.save(law)));
            log.info("법령 적재 title={} version={} chunks={} inserted={}", title, law.versionLabel(), law.parts().size(), inserted);
        }
    }

    @Override
    public void embed() {
        int total = 0;
        while (true) {
            var batch = repository.pendingEmbeddings(EmbeddingRepository.MODEL, 16);
            if (batch.isEmpty()) break;
            var vectors = embeddings.embed(batch.stream().map(LawImportRepository.EmbeddingInput::content).toList());
            transactions.executeWithoutResult(status -> repository.saveEmbeddings(EmbeddingRepository.MODEL, batch, vectors));
            total += batch.size();
            log.info("조문 임베딩 저장 completed={}", total);
        }
        log.info("임베딩 종료 inserted={}", total);
    }
}
