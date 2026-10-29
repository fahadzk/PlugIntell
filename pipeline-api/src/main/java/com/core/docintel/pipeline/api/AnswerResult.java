package com.core.docintel.pipeline.api;

import java.util.List;

public record AnswerResult(String query, String answer, boolean citationsEnabled, List<RetrievedChunk> citations) {
    public AnswerResult {
        if (query == null || query.isBlank()) throw new IllegalArgumentException("Search query is required.");
        if (answer == null || answer.isBlank()) throw new IllegalArgumentException("Answer must not be blank.");
        citations = List.copyOf(citations);
    }
}
