package com.core.docintel.plugins;

import com.core.docintel.pipeline.api.ChunkCollection;
import com.core.docintel.pipeline.api.EmbeddedChunk;
import com.core.docintel.pipeline.api.EmbeddingCollection;
import com.core.docintel.pipeline.api.PluginManifest;
import com.core.docintel.pipeline.api.PreflightContext;
import com.core.docintel.pipeline.api.PreflightPlugin;
import com.core.docintel.pipeline.api.TextChunk;
import com.core.docintel.ollama.OllamaConfig;
import com.core.docintel.ollama.OllamaModelAvailability;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.ollama.OllamaEmbeddingModel;

import java.util.ArrayList;
import java.util.List;

public final class OllamaEmbeddingPlugin implements PreflightPlugin {
    private static final PluginManifest MANIFEST = new PluginManifest(
            "ollama-embedding", "Ollama Text Embedding", "1.0.0", "1.0",
            "com.core.docintel.plugins.OllamaEmbeddingPlugin", "ChunkCollection", "EmbeddingCollection");
    private EmbeddingService embeddingService;

    public OllamaEmbeddingPlugin() { }

    OllamaEmbeddingPlugin(EmbeddingService embeddingService) {
        this.embeddingService = embeddingService;
    }

    @Override public PluginManifest manifest() { return MANIFEST; }

    @Override public void preflight() { embeddingService().preflight(); }

    @Override public void preflight(PreflightContext context) {
        context.put(PreflightContext.EMBEDDING_DIMENSIONS, embeddingService().preflight());
    }

    @Override
    public Object execute(Object input) {
        if (!(input instanceof ChunkCollection collection)) throw new IllegalArgumentException("Ollama embedding plugin expects a ChunkCollection.");
        List<EmbeddedChunk> embedded = new ArrayList<>(collection.chunks().size());
        int dimensions = -1;
        for (TextChunk chunk : collection.chunks()) {
            List<Float> vector = embeddingService().embed(chunk.text());
            if (vector == null || vector.isEmpty()) throw new IllegalStateException("Ollama returned an empty embedding for chunk " + chunk.id());
            if (dimensions < 0) dimensions = vector.size();
            else if (dimensions != vector.size()) throw new IllegalStateException("Ollama returned inconsistent vector dimensions: expected " + dimensions + " but got " + vector.size());
            embedded.add(new EmbeddedChunk(chunk, vector));
        }
        return new EmbeddingCollection(embedded);
    }

    private EmbeddingService embeddingService() {
        if (embeddingService == null) {
            embeddingService = new LangChain4jOllamaEmbeddingService(
                    OllamaConfig.fromEnvironment("OLLAMA_EMBEDDING_MODEL", "embedding"));
        }
        return embeddingService;
    }

    interface EmbeddingService {
        int preflight();
        List<Float> embed(String text);
    }

    private static final class LangChain4jOllamaEmbeddingService implements EmbeddingService {
        private final OllamaConfig config;
        private final EmbeddingModel model;

        private LangChain4jOllamaEmbeddingService(OllamaConfig config) {
            this.config = config;
            this.model = OllamaEmbeddingModel.builder()
                    .baseUrl(config.baseUrl())
                    .modelName(config.modelName())
                    .timeout(config.timeout())
                    .build();
        }

        @Override public int preflight() {
            OllamaModelAvailability.requireAvailable(config, "embedding");
            return embed("PlugIntell Ollama embedding preflight").size();
        }

        @Override public List<Float> embed(String text) {
            float[] values = model.embed(text).content().vector();
            List<Float> vector = new ArrayList<>(values.length);
            for (float value : values) {
                if (!Float.isFinite(value)) throw new IllegalStateException("Ollama returned a non-finite embedding value.");
                vector.add(value);
            }
            return List.copyOf(vector);
        }
    }
}
