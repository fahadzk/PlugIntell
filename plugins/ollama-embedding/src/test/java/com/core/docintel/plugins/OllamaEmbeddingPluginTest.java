package com.core.docintel.plugins;

import com.core.docintel.pipeline.api.ChunkCollection;
import com.core.docintel.pipeline.api.EmbeddingCollection;
import com.core.docintel.pipeline.api.TextChunk;
import org.junit.jupiter.api.Test;

import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class OllamaEmbeddingPluginTest {
    @Test void reportingConstructorEmitsConfirmedCountsWithoutChunkTextOrVectors(){
        java.util.List<com.core.docintel.pipeline.api.PluginMessage> messages=new java.util.ArrayList<>();
        var plugin=new OllamaEmbeddingPlugin(new FakeEmbedding(),messages::add);
        var input=new ChunkCollection("doc",List.of(new TextChunk("c","doc","private.pdf",1,0,"private document body")));
        assertEquals(1,((EmbeddingCollection)plugin.execute(input)).embeddings().size());
        assertEquals(1L,messages.getLast().payload().get("completed"));assertEquals(1L,messages.getLast().payload().get("total"));
        assertFalse(messages.toString().contains("private document body"));assertFalse(messages.toString().contains("private.pdf"));assertFalse(messages.toString().contains("vector"));
    }
    @Test void loadingPluginDoesNotNeedExternalConfigurationBeforePipelineValidation() {
        assertEquals("ollama-embedding", new OllamaEmbeddingPlugin().manifest().id());
    }

    @Test void preflightsAndEmbedsEachChunkWithoutLosingCitation() {
        FakeEmbedding service = new FakeEmbedding();
        var plugin = new OllamaEmbeddingPlugin(service);
        plugin.preflight();
        var input = new ChunkCollection("doc", List.of(
                new TextChunk("c1", "doc", "manual.pdf", 3, 0, "first"),
                new TextChunk("c2", "doc", "manual.pdf", 4, 1, "second")));
        var result = (EmbeddingCollection) plugin.execute(input);
        assertEquals(2, result.embeddings().size());
        assertEquals(3, result.embeddings().getFirst().chunk().pageNumber());
        assertEquals(List.of(1.0f, 2.0f), result.embeddings().getFirst().vector());
        assertEquals(3, service.calls);
    }

    @Test void rejectsInconsistentDimensions() {
        var service = new FakeEmbedding() {
            public List<Float> embed(String text) { return text.equals("first") ? List.of(1f, 2f) : List.of(1f); }
        };
        var input = new ChunkCollection("doc", List.of(
                new TextChunk("c1", "doc", "a.pdf", 1, 0, "first"),
                new TextChunk("c2", "doc", "a.pdf", 1, 1, "second")));
        assertThrows(IllegalStateException.class, () -> new OllamaEmbeddingPlugin(service).execute(input));
    }

    private static class FakeEmbedding implements OllamaEmbeddingPlugin.EmbeddingService {
        int calls;
        public int preflight() { calls++; return 2; }
        public List<Float> embed(String text) { calls++; return List.of(1f, 2f); }
    }
}
