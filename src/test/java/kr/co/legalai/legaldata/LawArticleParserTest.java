package kr.co.legalai.legaldata;

import kr.co.legalai.legaldata.service.impl.LawArticleParser;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class LawArticleParserTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final LawArticleParser parser = new LawArticleParser(mapper);
    private static final String ITEM = """
            {"법령ID":"001706","법령일련번호":"123","시행일자":"20260101",
             "법령명한글":"민법","현행연혁코드":"현행"}
            """;
    private static final String BODY = """
            {"법령":{"기본정보":{"법령ID":"001706","법령명_한글":"민법",
             "시행일자":"20260101","법종구분":{"content":"법률"}},
             "조문":{"조문단위":[
               {"조문여부":"전문","조문내용":"제1장"},
               {"조문여부":"조문","조문번호":"623","조문키":"0623001",
                "조문내용":"제623조(임대인의 의무)","항":{"항내용":"① 항",
                  "호":[{"호내용":"1. 호","목":{"목내용":"가. 목"}}]}},
               {"조문여부":"조문","조문번호":"650","조문키":"0650001","조문내용":"제650조 삭제"},
               {"조문여부":"조문","조문번호":"1","조문키":"0001001","조문내용":"제1조 법원"}]},
             "부칙":{"부칙단위":{"부칙내용":[["부칙","제1조 시행일"],["제2조 경과조치"]]}}}}
            """;

    @Test
    void preservesHierarchyAndNestedSupplementAndSelectsOnlyRelevantArticles() {
        var law = parser.parse("민법", mapper.readTree(ITEM), mapper.readTree(BODY));
        assertEquals(4, law.parts().size());
        assertTrue(law.parts().getFirst().content().contains("① 항\n1. 호\n가. 목"));
        assertEquals(java.util.List.of("housing_lease"), law.parts().getFirst().metadata().get("topic_tags"));
        assertEquals(java.util.List.of(), law.parts().get(1).metadata().get("topic_tags"));
        assertEquals(java.util.List.of(), law.parts().get(2).metadata().get("topic_tags"));
        assertTrue(law.parts().getLast().content().contains("부칙\n제1조 시행일\n제2조 경과조치"));
        assertFalse(law.sourceUrl().contains("OC="));
        assertEquals(law.contentHash(), parser.parse("민법", mapper.readTree(ITEM), mapper.readTree(BODY)).contentHash());
    }

    @Test
    void rejectsMismatchedVersionAndUnknownTextStructure() {
        assertThrows(IllegalStateException.class, () -> parser.parse("민법", mapper.readTree(ITEM),
                mapper.readTree(BODY.replace("20260101", "20260102"))));
        assertThrows(IllegalStateException.class, () -> parser.parse("민법", mapper.readTree(ITEM),
                mapper.readTree(BODY.replace("\"가. 목\"", "{\"unexpected\":true}"))));
    }

    @Test
    void splittingPreservesEveryUnicodeCharacterWithinByteLimit() {
        String input = "한글😀 조문\n".repeat(2000);
        var parts = LawArticleParser.split(input, 5500);
        assertTrue(parts.size() > 1);
        assertEquals(input, String.join("", parts));
        for (String part : parts) {
            assertTrue(part.getBytes(StandardCharsets.UTF_8).length <= 5500);
            assertEquals(part, new String(part.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8));
        }
    }
}
