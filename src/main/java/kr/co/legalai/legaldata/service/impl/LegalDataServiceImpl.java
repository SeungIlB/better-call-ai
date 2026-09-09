package kr.co.legalai.legaldata.service.impl;

import lombok.RequiredArgsConstructor;

import tools.jackson.databind.JsonNode;
import kr.co.legalai.common.exception.ExternalApiException;
import kr.co.legalai.legaldata.dto.response.LegalDocumentResponse;
import kr.co.legalai.legaldata.dto.response.LegalSearchItemResponse;
import kr.co.legalai.legaldata.dto.response.LegalSearchResponse;
import kr.co.legalai.legaldata.entity.LegalDocumentType;
import kr.co.legalai.legaldata.entity.LawKind;
import kr.co.legalai.legaldata.repository.LawOpenDataRepository;
import kr.co.legalai.legaldata.service.LegalDataService;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.DateTimeException;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.StreamSupport;

/**
 * 법제처 응답의 한글 필드와 문서별 구조 차이를 내부 응답 모델로 정규화한다.
 */
@Service
@RequiredArgsConstructor
public class LegalDataServiceImpl implements LegalDataService {
    private static final String LAW_HOST = "https://www.law.go.kr";
    private static final DateTimeFormatter BASIC_DATE = DateTimeFormatter.BASIC_ISO_DATE;
    private static final Pattern SEPARATED_DATE = Pattern.compile("(\\d{4})\\D+(\\d{1,2})\\D+(\\d{1,2})");

    private final LawOpenDataRepository repository;

    @Override
    public LegalSearchResponse search(LegalDocumentType type, String query, int page, int pageSize) {
        JsonNode response = repository.search(type, query.trim(), page, pageSize);
        JsonNode root = response.path(type.searchRoot());
        int totalCount = integer(root, "totalCnt", "총건수");
        JsonNode itemNode = root.path(type.searchItems());
        List<LegalSearchItemResponse> items = new ArrayList<>();
        if (itemNode.isArray()) {
            itemNode.forEach(node -> items.add(toSearchItem(type, node)));
        } else if (itemNode.isObject()) {
            items.add(toSearchItem(type, itemNode));
        }
        return new LegalSearchResponse(type, query.trim(), page, pageSize, totalCount, List.copyOf(items));
    }

    @Override
    public LegalDocumentResponse getDocument(LegalDocumentType type, String externalId) {
        JsonNode response = repository.findDocument(type, externalId.trim());
        String title = firstText(response, titleFields(type));
        if (title == null) {
            throw new ExternalApiException();
        }
        return new LegalDocumentResponse(
                type,
                firstTextOrDefault(response, externalId, idFields(type)),
                title,
                firstText(response, "사건번호"),
                firstText(response, publisherFields(type)),
                parseDate(firstText(response, dateFields(type))),
                parseDate(firstText(response, "시행일자")),
                absoluteUrl(firstText(response, linkFields(type))),
                flattenText(response),
                lawKind(type, response)
        );
    }

    private LegalSearchItemResponse toSearchItem(LegalDocumentType type, JsonNode node) {
        return new LegalSearchItemResponse(
                firstText(node, idFields(type)),
                firstText(node, titleFields(type)),
                firstText(node, "사건번호"),
                firstText(node, publisherFields(type)),
                parseDate(firstText(node, dateFields(type))),
                parseDate(firstText(node, "시행일자")),
                absoluteUrl(firstText(node, linkFields(type))),
                lawKind(type, node)
        );
    }

    private String[] idFields(LegalDocumentType type) {
        return type == LegalDocumentType.LAW
                ? new String[]{"법령ID", "법령일련번호"}
                : new String[]{"판례일련번호", "판례정보일련번호"};
    }

    private LawKind lawKind(LegalDocumentType type, JsonNode node) {
        if (type != LegalDocumentType.LAW) return null;
        // 본문은 기본정보만 확인하여 참조 법령/조문에 있는 다른 법종을 오인하지 않는다.
        JsonNode law = node.has("법령") ? node.path("법령") : node;
        JsonNode info = law.has("기본정보") ? law.path("기본정보") : law;
        JsonNode value = info.has("법령구분명") ? info.path("법령구분명") : info.path("법종구분");
        if (value.isObject()) value = value.path("content");
        return value.isString() ? LawKind.fromOfficialName(value.asString()) : null;
    }

    private String[] titleFields(LegalDocumentType type) {
        return type == LegalDocumentType.LAW
                ? new String[]{"법령명_한글", "법령명한글", "법령명"}
                : new String[]{"사건명"};
    }

    private String[] publisherFields(LegalDocumentType type) {
        return type == LegalDocumentType.LAW
                ? new String[]{"소관부처명", "소관부처"}
                : new String[]{"법원명"};
    }

    private String[] dateFields(LegalDocumentType type) {
        return type == LegalDocumentType.LAW
                ? new String[]{"공포일자"}
                : new String[]{"선고일자"};
    }

    private String[] linkFields(LegalDocumentType type) {
        return type == LegalDocumentType.LAW
                ? new String[]{"법령상세링크"}
                : new String[]{"판례상세링크"};
    }

    private int integer(JsonNode node, String... fieldNames) {
        String value = firstText(node, fieldNames);
        if (value == null) {
            return 0;
        }
        try {
            return Integer.parseInt(value.replace(",", ""));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String digits = value.replaceAll("[^0-9]", "");
        try {
            if (digits.length() == 8) {
                return LocalDate.parse(digits, BASIC_DATE);
            }
            var matcher = SEPARATED_DATE.matcher(value);
            if (matcher.find()) {
                return LocalDate.of(
                        Integer.parseInt(matcher.group(1)),
                        Integer.parseInt(matcher.group(2)),
                        Integer.parseInt(matcher.group(3))
                );
            }
            return null;
        } catch (DateTimeException | NumberFormatException ignored) {
            return null;
        }
    }

    private String absoluteUrl(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.startsWith("http") ? value : LAW_HOST + (value.startsWith("/") ? value : "/" + value);
    }

    private String firstTextOrDefault(JsonNode node, String defaultValue, String... fieldNames) {
        String value = firstText(node, fieldNames);
        return value == null ? defaultValue : value;
    }

    private String firstText(JsonNode node, String... fieldNames) {
        for (String fieldName : fieldNames) {
            JsonNode found = findField(node, fieldName);
            if (found != null && found.isValueNode() && !found.asString().isBlank()) {
                return found.asString().trim();
            }
        }
        return null;
    }

    private JsonNode findField(JsonNode node, String fieldName) {
        if (node == null) {
            return null;
        }
        JsonNode direct = node.get(fieldName);
        if (direct != null) {
            return direct;
        }
        for (JsonNode child : node) {
            JsonNode found = findField(child, fieldName);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private String flattenText(JsonNode root) {
        List<String> values = new ArrayList<>();
        collectText(root, values);
        return String.join("\n", values);
    }

    private void collectText(JsonNode node, List<String> values) {
        if (node.isString()) {
            String value = node.asString().trim();
            if (!value.isBlank()) {
                values.add(value);
            }
            return;
        }
        StreamSupport.stream(node.spliterator(), false).forEach(child -> collectText(child, values));
    }
}
