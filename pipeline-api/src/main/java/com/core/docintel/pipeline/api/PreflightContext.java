package com.core.docintel.pipeline.api;

import java.util.HashMap;
import java.util.Map;

/** Small per-validation context for sharing discovered facts between plugin preflight checks. */
public final class PreflightContext {
    public static final String EMBEDDING_DIMENSIONS = "embedding.dimensions";
    private final Map<String, Object> values = new HashMap<>();

    public void put(String key, Object value) {
        if (key == null || key.isBlank()) throw new IllegalArgumentException("Preflight context key is required.");
        if (value == null) throw new IllegalArgumentException("Preflight context values must not be null.");
        values.put(key, value);
    }

    public <T> T get(String key, Class<T> type) {
        Object value = values.get(key);
        return value == null ? null : type.cast(value);
    }
}
