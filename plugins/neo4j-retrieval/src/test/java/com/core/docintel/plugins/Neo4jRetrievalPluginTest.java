package com.core.docintel.plugins;

import com.core.docintel.pipeline.api.RetrievedChunk;
import com.core.docintel.pipeline.api.RetrievalResult;
import com.core.docintel.pipeline.api.SearchRequest;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class Neo4jRetrievalPluginTest {
    @Test void keepsQueryCitationSettingAndChunkReferences() {
        var chunk = new RetrievedChunk("chunk-1", "answer source", "book.pdf", 7, 0.91);
        var plugin = new Neo4jRetrievalPlugin(new FakeRetrieval(List.of(chunk)));
        plugin.preflight();
        var result = (RetrievalResult) plugin.execute(new SearchRequest("question", true));
        assertEquals("question", result.query());
        assertTrue(result.citationsEnabled());
        assertEquals("book.pdf", result.chunks().getFirst().sourceName());
        assertEquals(7, result.chunks().getFirst().pageNumber());
    }

    @Test void supportsCitationsDisabledWithoutDroppingStructuredProvenance() {
        var chunk = new RetrievedChunk("chunk-1", "answer source", "book.pdf", 7, 0.91);
        var result = (RetrievalResult) new Neo4jRetrievalPlugin(new FakeRetrieval(List.of(chunk)))
                .execute(new SearchRequest("question", false));
        assertFalse(result.citationsEnabled());
        assertEquals("book.pdf", result.chunks().getFirst().sourceName());
    }

    private record FakeRetrieval(List<RetrievedChunk> chunks) implements Neo4jRetrievalPlugin.RetrievalService {
        public void preflight(Integer embeddingDimensions) {}
        public List<RetrievedChunk> search(String query, int topK) { return chunks; }
        public void close() {}
    }
}
