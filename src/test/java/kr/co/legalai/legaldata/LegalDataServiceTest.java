package kr.co.legalai.legaldata;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import kr.co.legalai.common.exception.IntegrationNotConfiguredException;
import kr.co.legalai.legaldata.entity.LegalDocumentType;
import kr.co.legalai.legaldata.repository.LawOpenDataRepository;
import kr.co.legalai.legaldata.serviceimpl.LegalDataServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegalDataServiceTest {
    private HttpServer server;
    private String baseUrl;
    private final AtomicReference<String> lastQuery = new AtomicReference<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/lawSearch.do", exchange -> respond(exchange, searchResponse()));
        server.createContext("/lawService.do", exchange -> respond(exchange, precedentResponse()));
        server.start();
        baseUrl = "http://localhost:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void lawSearchResponseIsNormalized() {
        var repository = repository("test-oc");
        var service = new LegalDataServiceImpl(repository);

        var result = service.search(LegalDocumentType.LAW, "주택임대차보호법", 1, 20);

        assertEquals(1, result.totalCount());
        assertEquals("001234", result.items().getFirst().externalId());
        assertEquals("주택임대차보호법", result.items().getFirst().title());
        assertEquals(LocalDate.of(2025, 1, 31), result.items().getFirst().effectiveDate());
        assertEquals("https://www.law.go.kr/법령/주택임대차보호법", result.items().getFirst().sourceUrl());
        assertTrue(lastQuery.get().contains("target=law"));
        assertTrue(lastQuery.get().contains("OC=test-oc"));
    }

    @Test
    void precedentBodyIsNormalizedAndFlattened() {
        var repository = repository("test-oc");
        var service = new LegalDataServiceImpl(repository);

        var result = service.getDocument(LegalDocumentType.PRECEDENT, "7654321");

        assertEquals("임대차보증금", result.title());
        assertEquals("2024다12345", result.caseNumber());
        assertEquals(LocalDate.of(2025, 2, 13), result.publishedOrDecisionDate());
        assertTrue(result.normalizedText().contains("임대인은 목적물을 사용·수익할 수 있게 할 의무가 있다."));
        assertTrue(lastQuery.get().contains("target=prec"));
        assertTrue(lastQuery.get().contains("ID=7654321"));
    }

    @Test
    void missingOcFailsOnlyWhenIntegrationIsUsed() {
        var repository = repository("");

        assertThrows(
                IntegrationNotConfiguredException.class,
                () -> repository.search(LegalDocumentType.LAW, "민법", 1, 20)
        );
    }

    private LawOpenDataRepository repository(String oc) {
        return new LawOpenDataRepository(new ObjectMapper(), baseUrl, oc);
    }

    private void respond(HttpExchange exchange, String body) throws IOException {
        lastQuery.set(exchange.getRequestURI().getRawQuery());
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private String searchResponse() {
        return """
                {
                  "LawSearch": {
                    "totalCnt": "1",
                    "law": [
                      {
                        "법령ID": "001234",
                        "법령명한글": "주택임대차보호법",
                        "소관부처명": "법무부",
                        "공포일자": "20250101",
                        "시행일자": "20250131",
                        "법령상세링크": "/법령/주택임대차보호법"
                      }
                    ]
                  }
                }
                """;
    }

    private String precedentResponse() {
        return """
                {
                  "PrecService": {
                    "판례정보일련번호": "7654321",
                    "사건명": "임대차보증금",
                    "사건번호": "2024다12345",
                    "선고일자": "2025. 2. 13.",
                    "법원명": "대법원",
                    "판례상세링크": "/판례/2024다12345",
                    "판결요지": "임대인은 목적물을 사용·수익할 수 있게 할 의무가 있다."
                  }
                }
                """;
    }
}
