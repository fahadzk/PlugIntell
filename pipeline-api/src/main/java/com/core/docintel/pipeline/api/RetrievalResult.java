package com.core.docintel.pipeline.api;

import java.util.List;

public record RetrievalResult(String query, boolean citationsEnabled, List<RetrievedChunk> chunks,
                              java.util.Map<String,Object> graph) {
    public RetrievalResult(String query,boolean citationsEnabled,List<RetrievedChunk> chunks){this(query,citationsEnabled,chunks,java.util.Map.of());}
    public RetrievalResult {
        if (query == null || query.isBlank()) throw new IllegalArgumentException("Search query is required.");
        chunks = List.copyOf(chunks);
        graph = graph == null ? java.util.Map.of() : java.util.Map.copyOf(graph);
    }
    @Override public String toString(){return graph.isEmpty()?"RetrievalResult[query="+query+", citationsEnabled="+citationsEnabled+", chunks="+chunks+"]":"RetrievalResult[graphStrategy="+graph.get("strategy")+", evidenceChunks="+chunks.size()+"]";}
}
