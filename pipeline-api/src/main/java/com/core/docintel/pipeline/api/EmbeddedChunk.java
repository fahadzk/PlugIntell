package com.core.docintel.pipeline.api;

import java.util.List;

public record EmbeddedChunk(TextChunk chunk, List<Float> vector) {
    public EmbeddedChunk {
        if (chunk == null) throw new IllegalArgumentException("Chunk is required.");
        vector = List.copyOf(vector);
        if (vector.isEmpty()) throw new IllegalArgumentException("Embedding vector must not be empty.");
        if (vector.stream().anyMatch(v -> v == null || !Float.isFinite(v))) {
            throw new IllegalArgumentException("Embedding values must be finite.");
        }
    }
}
