package com.core.docintel.plugins;

import com.core.docintel.pipeline.api.EmbeddingCollection;
import com.core.docintel.pipeline.api.EmbeddedChunk;
import com.core.docintel.pipeline.api.StoredResult;
import com.core.docintel.pipeline.api.TextChunk;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class Neo4jWriterPluginTest {
    @Test void storesEmbeddingsAndReturnsCount() {
        var fake = new FakeWriter();
        var plugin = new Neo4jWriterPlugin(fake);
        var input = new EmbeddingCollection(List.of(new EmbeddedChunk(
                new TextChunk("id", "doc", "a.pdf", 2, 0, "text"), List.of(0.2f, 0.4f))));
        plugin.preflight();
        StoredResult result = (StoredResult) plugin.execute(input);
        assertEquals(1, result.storedChunks());
        assertEquals(1, fake.stored.embeddings().size());
    }

    @Test void reportsConnectivityAndAuthenticationPreflightFailure() {
        var unavailable = new FakeWriter() {
            public void preflight(Integer embeddingDimensions) {
                throw new IllegalStateException("Neo4j preflight failed: connection refused or authentication failed.");
            }
        };
        var error = assertThrows(IllegalStateException.class, () -> new Neo4jWriterPlugin(unavailable).preflight());
        assertTrue(error.getMessage().contains("authentication failed"));
    }

    private static class FakeWriter implements Neo4jWriterPlugin.WriterService {
        EmbeddingCollection stored;
        public void preflight(Integer embeddingDimensions) {}
        public int store(EmbeddingCollection collection) { stored = collection; return collection.embeddings().size(); }
        public void close() {}
    }
}
