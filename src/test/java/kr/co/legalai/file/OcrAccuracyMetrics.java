package kr.co.legalai.file;

import java.text.Normalizer;
import java.util.List;

/** 평가용 정규화는 공백과 PDF 페이지 표지만 제외한다. 숫자·부호·문장부호는 보존한다. */
final class OcrAccuracyMetrics {
    record Score(int characters, int edits, double cer, int criticalTotal, int criticalMatched) {
        boolean passed() { return cer <= 0.01 && criticalTotal == criticalMatched; }
    }

    static String normalize(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFC)
                .replaceAll("\\[페이지\\s+\\d+\\]", "").replaceAll("[\\s\\p{Z}]+", "");
    }

    static Score score(String expected, String actual, List<String> critical) {
        int[] left = normalize(expected).codePoints().toArray();
        int[] right = normalize(actual).codePoints().toArray();
        if (left.length == 0) throw new IllegalArgumentException("EMPTY_REFERENCE");
        int[] previous = new int[right.length + 1];
        for (int j = 0; j <= right.length; j++) previous[j] = j;
        for (int i = 1; i <= left.length; i++) {
            int[] current = new int[right.length + 1];
            current[0] = i;
            for (int j = 1; j <= right.length; j++) {
                current[j] = Math.min(previous[j] + 1, Math.min(current[j - 1] + 1,
                        previous[j - 1] + (left[i - 1] == right[j - 1] ? 0 : 1)));
            }
            previous = current;
        }
        String normalized = normalize(actual);
        int matched = (int) critical.stream().filter(value -> normalized.contains(normalize(value))).count();
        return new Score(left.length, previous[right.length], (double) previous[right.length] / left.length,
                critical.size(), matched);
    }
}
