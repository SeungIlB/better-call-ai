package kr.co.legalai.legaldata;

import com.sun.net.httpserver.HttpServer;
import kr.co.legalai.legaldata.repository.EmbeddingRepository;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class EmbeddingRepositoryTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void validatesRequestAndReordersVectorsByIndex() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/embeddings", exchange -> {
            var body = mapper.readTree(exchange.getRequestBody().readAllBytes());
            assertEquals(EmbeddingRepository.MODEL, body.path("model").asString());
            assertEquals(1536, body.path("dimensions").asInt());
            assertFalse(body.has("instructions"));
            float[] first = new float[1536]; first[0] = 1;
            float[] second = new float[1536]; second[1] = 1;
            byte[] bytes = mapper.writeValueAsBytes(Map.of("model", EmbeddingRepository.MODEL, "data",
                    List.of(Map.of("index", 1, "embedding", second), Map.of("index", 0, "embedding", first))));
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        server.start();
        try {
            var result = new EmbeddingRepository(mapper, "test-key", "http://127.0.0.1:" + server.getAddress().getPort())
                    .embed(List.of("첫 조문", "다음 조문"));
            assertEquals(1f, result.get(0)[0]);
            assertEquals(1f, result.get(1)[1]);
        } finally { server.stop(0); }
    }

    @Test
    void rejectsBadDimensionsAndDoesNotRetryFailuresOrExposeBody() throws Exception {
        var calls = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/embeddings", exchange -> {
            calls.incrementAndGet();
            byte[] bytes = mapper.writeValueAsBytes(Map.of("model", EmbeddingRepository.MODEL, "data",
                    List.of(Map.of("index", 0, "embedding", List.of(1)))));
            exchange.sendResponseHeaders(calls.get() == 1 ? 200 : 429, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        server.start();
        try {
            var repo = new EmbeddingRepository(mapper, "test-key", "http://127.0.0.1:" + server.getAddress().getPort());
            assertEquals("INVALID_EMBEDDING_RESPONSE", assertThrows(IllegalStateException.class,
                    () -> repo.embed(List.of("조문"))).getMessage());
            assertEquals("EMBEDDING_HTTP_429", assertThrows(IllegalStateException.class,
                    () -> repo.embed(List.of("조문"))).getMessage());
            assertEquals(2, calls.get());
            assertThrows(IllegalArgumentException.class, () -> repo.embed(List.of("가".repeat(2001))));
            assertEquals(2, calls.get());
        } finally { server.stop(0); }
    }

    @Test
    void missingKeyDoesNotMakeNetworkRequest() {
        assertEquals("OPENAI_API_KEY_MISSING", assertThrows(IllegalStateException.class,
                () -> new EmbeddingRepository(mapper, "", "http://127.0.0.1:1").embed(List.of("조문"))).getMessage());
    }
}
