package com.core.docintel.pipeline.api;

public record TextChunk(String id, String documentId, String sourceName, int pageNumber, int ordinal, String text) {
    public TextChunk {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("Chunk ID is required.");
        if (documentId == null || documentId.isBlank()) throw new IllegalArgumentException("Document ID is required.");
        if (sourceName == null || sourceName.isBlank()) throw new IllegalArgumentException("Source name is required.");
        if (pageNumber < 1 || ordinal < 0) throw new IllegalArgumentException("Invalid chunk location.");
        if (text == null || text.isBlank()) throw new IllegalArgumentException("Chunk text is required.");
    }
}
