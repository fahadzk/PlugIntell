package com.core.docintel.pipeline.api;

public record SearchRequest(String query, boolean includeCitations, int topK) {
    public SearchRequest {
        if (query == null || query.isBlank()) throw new IllegalArgumentException("Search query is required.");
        if (topK < 1 || topK > 100) throw new IllegalArgumentException("topK must be between 1 and 100.");
    }

    public SearchRequest(String query, boolean includeCitations) {
        this(query, includeCitations, 5);
    }
}
