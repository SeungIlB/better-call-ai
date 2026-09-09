package kr.co.legalai.legaldata.repository;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

/** 공개 법률 조문 임베딩 전용. 자동 재시도하지 않고 실패 시 이미 저장한 배치부터 재개한다. */
@Repository
public class EmbeddingRepository {
    public static final String MODEL = "text-embedding-3-small";
    private final ObjectMapper mapper;
    private final String key;
    private final URI endpoint;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    public EmbeddingRepository(ObjectMapper mapper, @Value("${integrations.openai.api-key:}") String key,
            @Value("${integrations.openai.base-url:https://api.openai.com/v1}") String baseUrl) {
        this.mapper = mapper; this.key = key; this.endpoint = URI.create(baseUrl + "/embeddings");
    }

    public List<float[]> embed(List<String> texts) {
        if (key == null || key.isBlank()) throw new IllegalStateException("OPENAI_API_KEY_MISSING");
        if (texts.isEmpty() || texts.size() > 16 || texts.stream().anyMatch(t -> t.isBlank()
                || t.getBytes(StandardCharsets.UTF_8).length > 6000)) throw new IllegalArgumentException("EMBEDDING_INPUT_LIMIT");
        try {
            var request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(30))
                    .header("Authorization", "Bearer " + key).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(Map.of(
                            "model", MODEL, "input", texts, "dimensions", 1536, "encoding_format", "float")))).build();
            var future = client.sendAsync(request, info -> new LimitedBody());
            HttpResponse<byte[]> response;
            try { response = future.get(30, TimeUnit.SECONDS); }
            finally { future.cancel(true); }
            if (response.statusCode() != 200) throw new IllegalStateException("EMBEDDING_HTTP_" + response.statusCode());
            JsonNode root = mapper.readTree(response.body());
            if (!MODEL.equals(root.path("model").asString()) || root.path("data").size() != texts.size()) throw invalid();
            float[][] result = new float[texts.size()][];
            for (JsonNode item : root.path("data")) {
                if (!item.path("index").isIntegralNumber() || !item.path("index").canConvertToInt()) throw invalid();
                int index = item.path("index").asInt(-1);
                if (index < 0 || index >= result.length || result[index] != null || item.path("embedding").size() != 1536) throw invalid();
                float[] vector = new float[1536];
                double norm = 0;
                for (int i = 0; i < vector.length; i++) {
                    JsonNode number = item.path("embedding").get(i);
                    if (number == null || !number.isNumber()) throw invalid();
                    vector[i] = (float) number.asDouble();
                    if (!Float.isFinite(vector[i])) throw invalid();
                    norm += (double) vector[i] * vector[i];
                }
                if (norm == 0) throw invalid();
                result[index] = vector;
            }
            return java.util.Arrays.asList(result);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("EMBEDDING_INTERRUPTED");
        } catch (IllegalStateException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException("EMBEDDING_REQUEST_FAILED");
        }
    }

    private IllegalStateException invalid() { return new IllegalStateException("INVALID_EMBEDDING_RESPONSE"); }

    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        public CompletionStage<byte[]> getBody() { return result; }
        public void onSubscribe(Flow.Subscription subscription) { this.subscription = subscription; subscription.request(1); }
        public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                if (buffer.remaining() > 2097152 - bytes.size()) {
                    subscription.cancel(); result.completeExceptionally(new IllegalStateException("EMBEDDING_BODY_LIMIT")); return;
                }
                byte[] part = new byte[buffer.remaining()]; buffer.get(part); bytes.writeBytes(part);
            }
            subscription.request(1);
        }
        public void onError(Throwable error) { result.completeExceptionally(error); }
        public void onComplete() { result.complete(bytes.toByteArray()); }
    }
}
