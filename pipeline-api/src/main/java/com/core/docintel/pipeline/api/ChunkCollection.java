package com.core.docintel.pipeline.api;

import java.util.List;

public record ChunkCollection(String documentId, List<TextChunk> chunks) {
    public ChunkCollection {
        if (documentId == null || documentId.isBlank()) throw new IllegalArgumentException("Document ID is required.");
        chunks = List.copyOf(chunks);
    }
}
