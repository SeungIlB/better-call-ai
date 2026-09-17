package kr.co.legalai.billing.repository;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;

@Repository
public class TossPaymentRepository {
    private final ObjectMapper mapper; private final String secret; private final HttpClient client;
    public TossPaymentRepository(ObjectMapper mapper, @Value("${integrations.toss.secret-key:}") String secret) {
        this.mapper = mapper; this.secret = secret; this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    }
    public void confirm(String paymentKey, String orderId, long amount) {
        if (secret.isBlank()) throw new IllegalStateException("PAYMENT_SECRET_MISSING");
        try {
            String auth = Base64.getEncoder().encodeToString((secret + ":").getBytes(StandardCharsets.UTF_8));
            var request = HttpRequest.newBuilder(URI.create("https://api.tosspayments.com/v1/payments/confirm"))
                    .timeout(Duration.ofSeconds(10)).header("Authorization", "Basic " + auth).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(Map.of("paymentKey", paymentKey, "orderId", orderId, "amount", amount)))).build();
            var response = client.send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() / 100 != 2) throw new IllegalStateException("PAYMENT_PROVIDER_REJECTED");
        } catch (Exception e) { throw new IllegalStateException("PAYMENT_PROVIDER_FAILED", e); }
    }
}
