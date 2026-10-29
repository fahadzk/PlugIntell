package com.core.docintel.plugins;

import com.core.docintel.neo4j.Neo4jChunkRepository;
import com.core.docintel.neo4j.Neo4jConfig;
import com.core.docintel.pipeline.api.EmbeddingCollection;
import com.core.docintel.pipeline.api.ManagedPlugin;
import com.core.docintel.pipeline.api.PluginManifest;
import com.core.docintel.pipeline.api.PreflightContext;
import com.core.docintel.pipeline.api.PreflightPlugin;
import com.core.docintel.pipeline.api.StoredResult;

public final class Neo4jWriterPlugin implements PreflightPlugin, ManagedPlugin {
    private static final PluginManifest MANIFEST = new PluginManifest(
            "neo4j-writer", "Neo4j Embedding Writer", "1.0.0", "1.0",
            "com.core.docintel.plugins.Neo4jWriterPlugin", "EmbeddingCollection", "StoredResult");
    private final WriterService writer;

    public Neo4jWriterPlugin() { this(new Neo4jWriterService()); }

    Neo4jWriterPlugin(WriterService writer) { this.writer = writer; }

    @Override public PluginManifest manifest() { return MANIFEST; }
    @Override public void preflight() { writer.preflight(null); }
    @Override public void preflight(PreflightContext context) {
        writer.preflight(context.get(PreflightContext.EMBEDDING_DIMENSIONS, Integer.class));
    }

    @Override public Object execute(Object input) {
        if (!(input instanceof EmbeddingCollection collection)) throw new IllegalArgumentException("Neo4j writer expects an EmbeddingCollection.");
        return new StoredResult(writer.store(collection));
    }

    @Override public void close() { writer.close(); }

    interface WriterService {
        void preflight(Integer embeddingDimensions);
        int store(EmbeddingCollection collection);
        void close();
    }

    private static final class Neo4jWriterService implements WriterService {
        private Neo4jChunkRepository repository;

        private Neo4jChunkRepository repository() {
            if (repository == null) repository = new Neo4jChunkRepository(Neo4jConfig.fromEnvironment());
            return repository;
        }

        @Override public void preflight(Integer embeddingDimensions) { repository().preflight(embeddingDimensions); }
        @Override public int store(EmbeddingCollection collection) { return repository().store(collection); }
        @Override public void close() { if (repository != null) repository.close(); }
    }
}
