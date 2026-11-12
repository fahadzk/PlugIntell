package com.core.docintel.pipeline.api;
import java.util.*;
/** Extraction handoff to graph writers. IDs are stable within a schema; chunks retain source provenance. */
public record ExtractedGraph(String documentId, Map<String,Object> schema,
        List<Map<String,Object>> entities, List<Map<String,Object>> relationships,
        List<TextChunk> chunks, Map<String,Object> metadata) {
    public ExtractedGraph {
        schema=Collections.unmodifiableMap(new LinkedHashMap<>(schema));
        entities=entities.stream().map(m->Collections.unmodifiableMap(new LinkedHashMap<>(m))).toList();
        relationships=relationships.stream().map(m->Collections.unmodifiableMap(new LinkedHashMap<>(m))).toList();
        chunks=List.copyOf(chunks);metadata=Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
    }
}
