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
        for (String title : List.of("민법", "주택임대차보호법", "주택임대차보호법 시행령")) {
            var matches = LawArticleParser.nodes(source.searchCurrentLaws(title).path("LawSearch").path("law"))
                    .stream().filter(row -> title.equals(row.path("법령명한글").asString())).toList();
            if (matches.size() != 1) throw new IllegalStateException("LAW_EXACT_MATCH_REQUIRED");
            var item = matches.getFirst();
            var law = parser.parse(title, item, source.findLawVersion(LawArticleParser.required(item, "법령일련번호"),
                    LawArticleParser.required(item, "시행일자")));
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
