package kr.co.legalai.file.service.impl;

import kr.co.legalai.file.dto.response.PiiFindingResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** OCR 확정 전에 사용자가 확인할 수 있는 형식 기반 개인정보 후보 탐지기. 원문은 저장하지 않는다. */
public final class PiiDetector {
    private static final List<Rule> RULES = List.of(
            new Rule("email", Pattern.compile("(?i)\\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}\\b")),
            new Rule("phone", Pattern.compile("(?<!\\d)01[016789]-?\\d{3,4}-?\\d{4}(?!\\d)")),
            new Rule("resident_registration", Pattern.compile("(?<!\\d)\\d{6}-?[1-4]\\d{6}(?!\\d)")),
            new Rule("business_registration", Pattern.compile("(?<!\\d)\\d{3}-?\\d{2}-?\\d{5}(?!\\d)"))
    );

    private PiiDetector() { }

    public static List<PiiFindingResponse> detect(String text) {
        if (text == null || text.isBlank()) return List.of();
        var result = new ArrayList<PiiFindingResponse>();
        for (Rule rule : RULES) {
            var matcher = rule.pattern().matcher(text);
            while (matcher.find()) result.add(new PiiFindingResponse(rule.type(), matcher.start(), matcher.end()));
        }
        return result.stream().sorted(java.util.Comparator.comparingInt(PiiFindingResponse::start)).toList();
    }

    private record Rule(String type, Pattern pattern) { }
}
