package kr.co.legalai.legaldata.repository;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import kr.co.legalai.common.exception.ExternalApiException;
import kr.co.legalai.common.exception.IntegrationNotConfiguredException;
import kr.co.legalai.legaldata.entity.LegalDocumentType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.function.Supplier;

/**
 * 법제처 국가법령정보 공동활용 API의 검색·본문 조회를 담당한다.
 */
@Repository
public class LawOpenDataRepository {
    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String oc;

    public LawOpenDataRepository(
            ObjectMapper objectMapper,
            @Value("${integrations.law-open-data.base-url:https://www.law.go.kr/DRF}") String baseUrl,
            @Value("${integrations.law-open-data.oc:}") String oc,
            @Value("${integrations.law-open-data.connect-timeout:2s}") Duration connectTimeout,
            @Value("${integrations.law-open-data.read-timeout:5s}") Duration readTimeout
    ) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(readTimeout);
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .build();
        this.objectMapper = objectMapper;
        this.oc = oc;
    }

    public JsonNode search(LegalDocumentType type, String query, int page, int pageSize) {
        requireConfigured();
        String response = exchange(() -> restClient.get()
                .uri(uriBuilder -> {
                    // 판례명 기본 검색으로는 누수·수선의무 등 본문의 쟁점을 놓친다.
                    if (type == LegalDocumentType.PRECEDENT) {
                        uriBuilder.queryParam("search", 2);
                    }
                    return uriBuilder
                        .path("/lawSearch.do")
                        .queryParam("OC", oc)
                        .queryParam("target", type.apiTarget())
                        .queryParam("type", "JSON")
                        .queryParam("query", query)
                        .queryParam("display", pageSize)
                        .queryParam("page", page)
                        .build();
                })
                .retrieve()
                .body(String.class));
        return readJson(response);
    }

    public JsonNode findDocument(LegalDocumentType type, String externalId) {
        requireConfigured();
        String response = exchange(() -> restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/lawService.do")
                        .queryParam("OC", oc)
                        .queryParam("target", type.apiTarget())
                        .queryParam("type", "JSON")
                        .queryParam("ID", externalId)
                        .build())
                .retrieve()
                .body(String.class));
        return readJson(response);
    }

    private void requireConfigured() {
        if (oc == null || oc.isBlank()) {
            throw new IntegrationNotConfiguredException();
        }
    }

    private JsonNode readJson(String response) {
        if (response == null || response.isBlank()) {
            throw new ExternalApiException();
        }
        try {
            return objectMapper.readTree(response);
        } catch (JacksonException exception) {
            throw new ExternalApiException(exception);
        }
    }

    private String exchange(Supplier<String> request) {
        try {
            return request.get();
        } catch (RestClientException exception) {
            throw new ExternalApiException(exception);
        }
    }
}
