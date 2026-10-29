package com.core.docintel.pipeline.api;

public record RetrievedChunk(String id, String text, String sourceName, int pageNumber, double score) {
    public RetrievedChunk {
        if (id == null || id.isBlank() || text == null || sourceName == null || sourceName.isBlank()) {
            throw new IllegalArgumentException("Retrieved chunk identity, text, and source are required.");
        }
        if (pageNumber < 1 || !Double.isFinite(score)) throw new IllegalArgumentException("Invalid retrieved chunk location or score.");
    }
}
