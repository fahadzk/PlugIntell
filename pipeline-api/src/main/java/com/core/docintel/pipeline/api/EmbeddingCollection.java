package com.core.docintel.pipeline.api;

import java.util.List;

public record EmbeddingCollection(List<EmbeddedChunk> embeddings) {
    public EmbeddingCollection {
        embeddings = List.copyOf(embeddings);
        int dimensions = embeddings.isEmpty() ? 0 : embeddings.getFirst().vector().size();
        if (embeddings.stream().anyMatch(e -> e.vector().size() != dimensions)) {
            throw new IllegalArgumentException("Embedding vectors must have consistent dimensions.");
        }
    }
}
