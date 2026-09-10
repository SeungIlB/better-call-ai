package kr.co.legalai.legaldata;

import kr.co.legalai.legaldata.repository.EmbeddingRepository;
import kr.co.legalai.legaldata.repository.LawImportRepository;
import kr.co.legalai.legaldata.repository.LawOpenDataRepository;
import kr.co.legalai.legaldata.service.impl.LawArticleParser;
import kr.co.legalai.legaldata.service.impl.LawImportServiceImpl;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** .env 자격 증명으로 명시적으로 실행하는 로컬 적재 명령. 웹 서버에는 공개하지 않는다. */
public class LegalDataImportCommand {
    public static void main(String[] args) {
        try { run(args); }
        catch (Exception failure) {
            // HTTP 예외/SQL/설정값을 그대로 출력하지 않는다.
            String message = failure instanceof IllegalStateException ? failure.getMessage() : "IMPORT_FAILED";
            if (message == null || !message.matches("[A-Z_0-9]+")) message = "IMPORT_FAILED";
            System.err.println(message);
            System.exit(1);
        }
    }

    private static void run(String[] args) throws Exception {
        if (args.length != 1 || !List.of("collect", "embed", "verify", "search-check", "search-evaluate", "draft-evaluate").contains(args[0])) {
            throw new IllegalStateException("INVALID_LEGAL_DATA_COMMAND");
        }
        Map<String, String> config = new HashMap<>();
        for (String line : Files.readAllLines(Path.of(".env"))) {
            int equals = line.indexOf('=');
            if (equals < 1 || line.stripLeading().startsWith("#")) continue;
            String value = line.substring(equals + 1).trim();
            if (value.length() >= 2 && ((value.startsWith("\"") && value.endsWith("\""))
                    || (value.startsWith("'") && value.endsWith("'")))) value = value.substring(1, value.length() - 1);
            config.put(line.substring(0, equals).trim(), value);
        }
        for (String name : List.of("DRAFT_EVAL_MODEL", "DRAFT_EVAL_REASONING", "DRAFT_EVAL_OUTPUT_TOKENS")) {
            if (System.getenv(name) != null) config.put(name, System.getenv(name));
        }
        String url = required(config, "DATABASE_URL");
        if (!url.matches("jdbc:postgresql://(localhost|127\\.0\\.0\\.1):5433/legal_ai")) {
            throw new IllegalStateException("LOCAL_PROJECT_DATABASE_REQUIRED");
        }
        var dataSource = new DriverManagerDataSource(url, required(config, "DATABASE_MIGRATION_USER"),
                required(config, "DATABASE_MIGRATION_PASSWORD"));
        if (args[0].equals("collect")) required(config, "LAW_OPEN_DATA_OC");
        if (List.of("embed", "search-check", "search-evaluate", "draft-evaluate").contains(args[0])) required(config, "OPENAI_API_KEY");
        // 별도 세션 잠금으로 동시 운영 명령의 중복 생성·과금을 방지한다. 사용자 요청용 풀과 무관하다.
        try (var lock = dataSource.getConnection(); var statement = lock.createStatement()) {
            try (var row = statement.executeQuery("SELECT pg_try_advisory_lock(732019)")) {
                row.next(); if (!row.getBoolean(1)) throw new IllegalStateException("IMPORT_ALREADY_RUNNING");
            }
            if (args[0].equals("collect")) Flyway.configure().dataSource(dataSource).cleanDisabled(true)
                    .locations("classpath:db/migration").load().migrate();
            var mapper = new ObjectMapper();
            var repository = new LawImportRepository(new JdbcTemplate(dataSource), mapper);
            var embeddings = new EmbeddingRepository(mapper, config.getOrDefault("OPENAI_API_KEY", ""), "https://api.openai.com/v1");
            var service = new LawImportServiceImpl(new LawOpenDataRepository(mapper, "https://www.law.go.kr/DRF",
                    config.getOrDefault("LAW_OPEN_DATA_OC", ""), Duration.ofSeconds(3), Duration.ofSeconds(30)),
                    new LawArticleParser(mapper), repository, embeddings,
                    new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
            switch (args[0]) {
                case "collect" -> service.collect();
                case "embed" -> service.embed();
                case "search-evaluate" -> LegalSearchEvaluation.run(new JdbcTemplate(dataSource), embeddings, mapper);
                case "draft-evaluate" -> LegalDraftEvaluation.run(new JdbcTemplate(dataSource), config, mapper);
                case "search-check" -> {
                    String query = "임대인이 집 수리를 해주지 않아 제가 수리비를 냈습니다. 돌려받을 수 있나요?";
                    String expanded = kr.co.legalai.legaldata.service.impl.HousingSearchTerms.expand(query);
                    var vector = embeddings.embed(List.of(query, expanded));
                    repository.search(vector.getFirst()).forEach(System.out::println);
                    new kr.co.legalai.legaldata.repository.LegalEvidenceSearchRepository(new JdbcTemplate(dataSource))
                            .search(expanded, vector.get(1)).forEach(item -> System.out.println(
                                    "HYBRID heading=" + item.heading() + " rankScore=" + item.rankScore()
                                            + " source=" + item.sourceUrl()));
                }
                default -> { }
            }
            repository.summary().forEach(System.out::println);
        }
    }

    private static String required(Map<String, String> config, String key) {
        String value = config.get(key);
        if (value == null || value.isBlank()) throw new IllegalStateException(key + "_MISSING");
        return value;
    }
}
