package com.core.docintel.pipeline.api;

public record StoredResult(int storedChunks) {
    public StoredResult {
        if (storedChunks < 0) throw new IllegalArgumentException("Stored chunk count cannot be negative.");
    }
}
