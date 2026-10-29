package com.core.docintel.plugins;

import com.core.docintel.neo4j.Neo4jChunkRepository;
import com.core.docintel.neo4j.Neo4jConfig;
import com.core.docintel.ollama.OllamaConfig;
import com.core.docintel.ollama.OllamaModelAvailability;
import com.core.docintel.pipeline.api.ManagedPlugin;
import com.core.docintel.pipeline.api.PluginManifest;
import com.core.docintel.pipeline.api.PreflightContext;
import com.core.docintel.pipeline.api.PreflightPlugin;
import com.core.docintel.pipeline.api.RetrievalResult;
import com.core.docintel.pipeline.api.SearchRequest;
import com.core.docintel.pipeline.api.RetrievedChunk;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.ollama.OllamaEmbeddingModel;

import java.util.ArrayList;
import java.util.List;

public final class Neo4jRetrievalPlugin implements PreflightPlugin, ManagedPlugin {
    private static final PluginManifest MANIFEST = new PluginManifest(
            "neo4j-retrieval", "Interactive Semantic Retrieval", "1.0.0", "1.0",
            "com.core.docintel.plugins.Neo4jRetrievalPlugin", "SearchRequest", "RetrievalResult");
    private final RetrievalService retrieval;

    public Neo4jRetrievalPlugin() { this(new OllamaNeo4jRetrievalService()); }
    Neo4jRetrievalPlugin(RetrievalService retrieval) { this.retrieval = retrieval; }

    @Override public PluginManifest manifest() { return MANIFEST; }
    @Override public void preflight() { retrieval.preflight(null); }
    @Override public void preflight(PreflightContext context) {
        retrieval.preflight(context.get(PreflightContext.EMBEDDING_DIMENSIONS, Integer.class));
    }

    @Override public Object execute(Object input) {
        if (!(input instanceof SearchRequest request)) throw new IllegalArgumentException("Neo4j retrieval plugin expects a SearchRequest.");
        List<RetrievedChunk> chunks = retrieval.search(request.query(), request.topK());
        return new RetrievalResult(request.query(), request.includeCitations(), chunks);
    }

    @Override public void close() { retrieval.close(); }

    interface RetrievalService {
        void preflight(Integer embeddingDimensions);
        List<RetrievedChunk> search(String query, int topK);
        void close();
    }

    private static final class OllamaNeo4jRetrievalService implements RetrievalService {
        private OllamaConfig ollamaConfig;
        private Neo4jChunkRepository repository;
        private EmbeddingModel embeddingModel;

        private void configure() {
            if (ollamaConfig == null) {
                ollamaConfig = OllamaConfig.fromEnvironment("OLLAMA_EMBEDDING_MODEL", "embedding");
                embeddingModel = OllamaEmbeddingModel.builder().baseUrl(ollamaConfig.baseUrl())
                        .modelName(ollamaConfig.modelName()).timeout(ollamaConfig.timeout()).build();
                repository = new Neo4jChunkRepository(Neo4jConfig.fromEnvironment());
            }
        }

        @Override public void preflight(Integer expectedDimensions) {
            configure();
            OllamaModelAvailability.requireAvailable(ollamaConfig, "embedding");
            float[] probe = embeddingModel.embed("PlugIntell retrieval preflight").content().vector();
            if (probe.length == 0) throw new IllegalStateException("Ollama returned an empty query embedding during preflight.");
            if (expectedDimensions != null && probe.length != expectedDimensions) {
                throw new IllegalStateException("Ollama query embedding has " + probe.length
                        + " dimensions but the prior embedding plugin reported " + expectedDimensions + ".");
            }
            repository.preflight(probe.length);
        }

        @Override public List<RetrievedChunk> search(String query, int topK) {
            configure();
            float[] values = embeddingModel.embed(query).content().vector();
            List<Float> vector = new ArrayList<>(values.length);
            for (float value : values) {
                if (!Float.isFinite(value)) throw new IllegalStateException("Ollama returned a non-finite query embedding value.");
                vector.add(value);
            }
            return repository.search(vector, topK);
        }

        @Override public void close() { if (repository != null) repository.close(); }
    }
}
