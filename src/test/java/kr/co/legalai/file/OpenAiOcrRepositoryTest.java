package kr.co.legalai.file;

import com.sun.net.httpserver.HttpServer;
import kr.co.legalai.common.exception.BusinessException;
import kr.co.legalai.common.exception.ErrorCode;
import kr.co.legalai.file.repository.OpenAiOcrRepository;
import org.junit.jupiter.api.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class OpenAiOcrRepositoryTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private HttpServer server;
    private final AtomicReference<String> body = new AtomicReference<>();
    private final AtomicReference<JsonNode> sent = new AtomicReference<>();
    private final AtomicInteger calls = new AtomicInteger();
    private int status = 200;

    @BeforeEach
    void start() throws Exception {
        body.set(success("계약서\n금액 100,000원\n"));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/responses", exchange -> {
            calls.incrementAndGet();
            assertEquals("Bearer test-key", exchange.getRequestHeaders().getFirst("Authorization"));
            sent.set(mapper.readTree(exchange.getRequestBody().readAllBytes()));
            byte[] bytes = body.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (var out = exchange.getResponseBody()) { out.write(bytes); }
        });
        server.start();
    }

    @AfterEach
    void stop() { server.stop(0); }

    private OpenAiOcrRepository client(String key, String model) {
        return new OpenAiOcrRepository(mapper, "http://127.0.0.1:" + server.getAddress().getPort(),
                key, model, Duration.ofSeconds(2));
    }

    private String success(String text) {
        return mapper.writeValueAsString(java.util.Map.of("id", "resp_ocr", "model", "vision-test",
                "status", "completed", "usage", java.util.Map.of("input_tokens", 50, "output_tokens", 12),
                "output", List.of(java.util.Map.of("type", "message", "role", "assistant", "content",
                        List.of(java.util.Map.of("type", "output_text", "text", text))))));
    }

    @Test
    void imagesUseInlineDataAndOcrInstructionWithoutRemoteFileStorage() {
        for (String mime : List.of("image/png", "image/jpeg", "image/webp")) {
            var result = client("test-key", "vision-test").extract(new byte[]{1, 2, 3}, mime);
            assertEquals("계약서\n금액 100,000원\n", result.text());
            assertEquals(50, result.inputTokens());
            assertEquals(12, result.outputTokens());
            JsonNode request = sent.get();
            assertFalse(request.path("store").asBoolean());
            assertFalse(request.path("stream").asBoolean());
            assertEquals(16000, request.path("max_output_tokens").asInt());
            assertTrue(request.path("instructions").asString().contains("전사"));
            JsonNode attachment = request.path("input").get(0).path("content").get(1);
            assertEquals("input_image", attachment.path("type").asString());
            assertEquals("data:" + mime + ";base64,AQID", attachment.path("image_url").asString());
            assertEquals("high", attachment.path("detail").asString());
            assertTrue(attachment.path("file_id").isMissingNode());
        }
    }

    @Test
    void pdfUsesInputFileWithGenericFilename() {
        client("test-key", "vision-test").extract(new byte[]{1}, "application/pdf");
        JsonNode attachment = sent.get().path("input").get(0).path("content").get(1);
        assertEquals("input_file", attachment.path("type").asString());
        assertEquals("document.pdf", attachment.path("filename").asString());
        assertEquals("data:application/pdf;base64,AQ==", attachment.path("file_data").asString());
    }

    @Test
    void incompleteRefusedMalformedAndOversizedResultsAreNeverAccepted() {
        for (String response : List.of(
                success("부분 결과").replace("\"completed\"", "\"incomplete\""),
                "{\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"refusal\"}]}]}",
                "not-json", success(" "), success("x".repeat(100001)), success("x" + (char) 0),
                success("정상").replace("\"input_tokens\":50", "\"input_tokens\":-1"),
                "x".repeat(1048577))) {
            body.set(response);
            var error = assertThrows(BusinessException.class,
                    () -> client("test-key", "vision-test").extract(new byte[]{1}, "image/png"));
            assertEquals(ErrorCode.OCR_UNAVAILABLE, error.getErrorCode());
            assertNull(error.getCause());
        }
        assertEquals(8, calls.get());
    }

    @Test
    void errorsAreSanitizedAndNeverAutomaticallyRetried() {
        body.set("private-provider-body");
        for (int code : List.of(400, 401, 429, 500)) {
            status = code;
            var error = assertThrows(BusinessException.class,
                    () -> client("test-key", "vision-test").extract(new byte[]{1}, "image/png"));
            assertEquals(ErrorCode.OCR_UNAVAILABLE, error.getErrorCode());
            assertFalse(error.toString().contains("private-provider-body"));
            assertNull(error.getCause());
        }
        assertEquals(4, calls.get());
    }

    @Test
    void missingConfigurationAndInvalidFilesMakeNoRequests() {
        assertEquals(ErrorCode.INTEGRATION_NOT_CONFIGURED, assertThrows(BusinessException.class,
                () -> client("", "vision-test").extract(new byte[]{1}, "image/png")).getErrorCode());
        assertEquals(ErrorCode.INTEGRATION_NOT_CONFIGURED, assertThrows(BusinessException.class,
                () -> client("test-key", "").extract(new byte[]{1}, "image/png")).getErrorCode());
        assertThrows(BusinessException.class, () -> client("test-key", "vision-test").extract(new byte[0], "image/png"));
        assertThrows(BusinessException.class, () -> client("test-key", "vision-test").extract(new byte[]{1}, "text/plain"));
        assertEquals(0, calls.get());
    }

    @Test
    void stalledBodyHasAnOverallTimeout() throws Exception {
        server.removeContext("/responses");
        server.createContext("/responses", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(200, 100);
            try {
                Thread.sleep(1000);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        var shortClient = new OpenAiOcrRepository(mapper, "http://127.0.0.1:" + server.getAddress().getPort(),
                "test-key", "vision-test", Duration.ofMillis(200));
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> assertEquals(ErrorCode.OCR_UNAVAILABLE,
                assertThrows(BusinessException.class, () -> shortClient.extract(new byte[]{1}, "image/png")).getErrorCode()));
    }
}
