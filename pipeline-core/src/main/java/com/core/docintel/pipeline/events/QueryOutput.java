package com.core.docintel.pipeline.events;

import com.core.docintel.pipeline.api.AnswerResult;
import java.util.*;

/** User-facing output, deliberately separate from telemetry and template snapshots. */
final class QueryOutput {
    static final int MAX_ANSWER_CHARACTERS = 262_144;
    static final int MAX_CITATIONS = 100;

    private QueryOutput() {}

    static Map<String,Object> of(Object output) {
        if (!(output instanceof AnswerResult) && !(output instanceof String)) return Map.of();
        String answer = output instanceof AnswerResult result ? result.answer() : (String) output;
        Map<String,Object> value = new LinkedHashMap<>();
        value.put("available", true);
        value.put("type", output instanceof AnswerResult ? "AnswerResult" : "String");
        value.put("answer", bounded(answer, MAX_ANSWER_CHARACTERS));
        value.put("truncated", answer.length() > MAX_ANSWER_CHARACTERS);
        if (output instanceof AnswerResult result) {
            value.put("query", bounded(result.query(), 16_384));
            value.put("citationsEnabled", result.citationsEnabled());
            value.put("citationCount", result.citations().size());
            value.put("citationsTruncated", result.citations().size() > MAX_CITATIONS);
            value.put("citations", result.citations().stream().limit(MAX_CITATIONS).map(chunk -> Map.of(
                "id", bounded(chunk.id(), 256), "sourceName", bounded(chunk.sourceName(), 1024),
                "pageNumber", chunk.pageNumber(), "score", chunk.score())).toList());
        } else value.put("citations", List.of());
        return Collections.unmodifiableMap(value);
    }

    private static String bounded(String text, int limit) {
        int end = Math.min(text.length(), limit);
        if (end < text.length() && end > 0 && Character.isHighSurrogate(text.charAt(end - 1))) end--;
        return text.substring(0, end);
    }
}
