package kr.co.legalai.legaldata.service.impl;

import kr.co.legalai.legaldata.entity.CollectedLaw;
import kr.co.legalai.legaldata.entity.LawKind;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 조문 계층만 추출한다. 전체 원본 JSON은 별도로 보존하고 불명확한 구조는 적재하지 않는다. */
@Component
@RequiredArgsConstructor
public class LawArticleParser {
    public static final Map<String, String> TARGETS = Map.of(
            "민법", "001706", "주택임대차보호법", "001248", "주택임대차보호법 시행령", "004950");
    private static final Set<Integer> RELATED_CIVIL_ARTICLES = Set.of(390, 393, 536, 543, 544, 548, 550, 580);
    private final ObjectMapper mapper;

    public CollectedLaw parse(String title, JsonNode item, JsonNode response) {
        String expectedId = TARGETS.get(title);
        String id = required(item, "법령ID");
        String serial = required(item, "법령일련번호");
        String date = required(item, "시행일자");
        if (expectedId == null || !id.equals(expectedId) || !serial.matches("[0-9]+")
                || !title.equals(required(item, "법령명한글")) || !"현행".equals(required(item, "현행연혁코드"))) {
            throw invalid();
        }
        JsonNode law = response.path("법령");
        JsonNode info = law.path("기본정보");
        if (!id.equals(required(info, "법령ID")) || !title.equals(required(info, "법령명_한글"))
                || !date.equals(required(info, "시행일자"))) throw invalid();
        LocalDate effective = LocalDate.parse(date, DateTimeFormatter.BASIC_ISO_DATE);
        if (effective.isAfter(LocalDate.now(java.time.ZoneOffset.UTC))) throw invalid();
        JsonNode kindNode = info.path("법종구분");
        LawKind kind = LawKind.fromOfficialName(kindNode.isString() ? kindNode.asString() : required(kindNode, "content"));
        if (kind == null) throw invalid();
        List<CollectedLaw.Part> parts = new ArrayList<>();
        Set<String> articleKeys = new java.util.HashSet<>();
        for (JsonNode article : nodes(law.path("조문").path("조문단위"))) {
            if (!"조문".equals(required(article, "조문여부"))) continue; // 편·장·절 제목은 원본에 보존
            String number = required(article, "조문번호");
            String branch = article.path("조문가지번호").asString("");
            String key = required(article, "조문키");
            if (!articleKeys.add(key)) throw invalid();
            String heading = "제" + number + "조" + (branch.isBlank() ? "" : "의" + branch);
            String text = text(article, "조문내용") + descendants(article);
            if (text.isBlank()) throw invalid();
            boolean deleted = text.matches("(?s)^제\\d+조(?:의\\d+)?\\s+삭제.*");
            String articleDate = article.path("조문시행일자").asString(date);
            if (articleDate.isBlank()) articleDate = date;
            LocalDate starts = LocalDate.parse(articleDate, DateTimeFormatter.BASIC_ISO_DATE);
            int articleNo = Integer.parseInt(number);
            boolean selected = !deleted && !starts.isAfter(LocalDate.now(java.time.ZoneOffset.UTC))
                    && (!id.equals("001706") || (articleNo >= 618 && articleNo <= 654)
                    || RELATED_CIVIL_ARTICLES.contains(articleNo));
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("article_key", key);
            metadata.put("article_number", number);
            metadata.put("article_branch", branch);
            metadata.put("effective_from", starts.toString());
            metadata.put("deleted", deleted);
            metadata.put("topic_tags", selected ? List.of("housing_lease") : List.of());
            metadata.put("selection_rule", "housing-lease-v1");
            addParts(parts, title + " " + heading, "article", text, metadata);
        }
        if (articleKeys.isEmpty()) throw invalid();
        int supplement = 0;
        for (JsonNode addendum : nodes(law.path("부칙").path("부칙단위"))) {
            String text = text(addendum, "부칙내용");
            if (text.isBlank()) throw invalid();
            addParts(parts, title + " 부칙 " + (++supplement), "supplement", text,
                    Map.of("topic_tags", List.of(), "notice_number", addendum.path("부칙공포번호").asString(""),
                            "notice_date", addendum.path("부칙공포일자").asString("")));
        }
        String raw = mapper.writeValueAsString(canonical(law));
        return CollectedLaw.builder().externalId(id).title(title).serial(serial).effectiveFrom(effective)
                .kind(kind).sourceUrl("https://www.law.go.kr/LSW/lsInfoP.do?lsiSeq=" + serial + "&efYd=" + date)
                .rawJson(raw).contentHash(hash(raw)).parts(parts).build();
    }

    private void addParts(List<CollectedLaw.Part> parts, String heading, String type, String body, Map<String, Object> base) {
        List<String> slices = split(body, 5500);
        for (int i = 0; i < slices.size(); i++) {
            Map<String, Object> metadata = new LinkedHashMap<>(base);
            metadata.put("part", i + 1);
            metadata.put("parts", slices.size());
            metadata.put("parser_version", "law-article-v1");
            String content = heading + "\n" + slices.get(i);
            if (content.getBytes(StandardCharsets.UTF_8).length > 6000) throw invalid();
            metadata.put("content_hash", hash(content));
            parts.add(CollectedLaw.Part.builder().heading(heading).type(type).content(content).metadata(metadata).build());
        }
    }

    // 바이트 기반 보수적 상한: UTF-8 토큰화의 입력 한도를 넘지 않도록 하고 한글/서로게이트를 자르지 않는다.
    public static List<String> split(String text, int limit) {
        List<String> result = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int end = start, bytes = 0, newline = -1;
            while (end < text.length()) {
                int cp = text.codePointAt(end);
                int length = new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8).length;
                if (bytes + length > limit) break;
                bytes += length;
                end += Character.charCount(cp);
                if (cp == '\n') newline = end;
            }
            if (end == start) throw invalid();
            if (end < text.length() && newline > start + (end - start) / 2) end = newline;
            result.add(text.substring(start, end));
            start = end;
        }
        return result;
    }

    private String descendants(JsonNode node) {
        StringBuilder result = new StringBuilder();
        for (String level : List.of("항", "호", "목")) {
            for (JsonNode child : nodes(node.path(level))) {
                String content = text(child, level + "내용");
                if (!content.isBlank()) result.append('\n').append(content);
                result.append(descendants(child));
            }
        }
        return result.toString();
    }

    private String text(JsonNode node, String field) {
        return textValue(node.path(field));
    }

    private String textValue(JsonNode value) {
        if (value.isMissingNode() || value.isNull()) return "";
        if (value.isString()) return value.asString();
        if (value.isArray()) {
            List<String> lines = new ArrayList<>();
            for (JsonNode line : value) { lines.add(textValue(line)); }
            return String.join("\n", lines);
        }
        throw invalid();
    }

    public static List<JsonNode> nodes(JsonNode node) {
        if (node.isMissingNode() || node.isNull() || (node.isString() && node.asString().isBlank())) return List.of();
        if (node.isObject()) return List.of(node);
        if (!node.isArray()) throw invalid();
        List<JsonNode> result = new ArrayList<>();
        node.forEach(result::add);
        return result;
    }

    private JsonNode canonical(JsonNode node) {
        if (node.isObject()) {
            var result = mapper.createObjectNode();
            node.properties().stream().sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> result.set(entry.getKey(), canonical(entry.getValue())));
            return result;
        }
        if (node.isArray()) { var result = mapper.createArrayNode(); node.forEach(child -> result.add(canonical(child))); return result; }
        return node;
    }

    public static String hash(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException failure) { throw new IllegalStateException("SHA-256 unavailable"); }
    }

    public static String required(JsonNode node, String name) {
        JsonNode value = node.path(name);
        if (!value.isValueNode() || value.isNull() || value.asString().isBlank()) throw invalid();
        return value.asString();
    }

    private static IllegalStateException invalid() { return new IllegalStateException("LAW_SOURCE_VALIDATION_FAILED"); }
}
