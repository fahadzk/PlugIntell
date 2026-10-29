package com.core.docintel.pipeline.api;

import java.util.List;

public record RetrievalResult(String query, boolean citationsEnabled, List<RetrievedChunk> chunks) {
    public RetrievalResult {
        if (query == null || query.isBlank()) throw new IllegalArgumentException("Search query is required.");
        chunks = List.copyOf(chunks);
    }
}
