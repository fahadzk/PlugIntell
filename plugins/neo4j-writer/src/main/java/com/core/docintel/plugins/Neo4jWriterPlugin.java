package com.core.docintel.plugins;

import com.core.docintel.neo4j.Neo4jChunkRepository;
import com.core.docintel.neo4j.Neo4jConfig;
import com.core.docintel.pipeline.api.EmbeddingCollection;
import com.core.docintel.pipeline.api.ConfigurablePlugin;
import com.core.docintel.pipeline.api.ManagedPlugin;
import com.core.docintel.pipeline.api.PluginManifest;
import com.core.docintel.pipeline.api.PreflightContext;
import com.core.docintel.pipeline.api.PreflightPlugin;
import com.core.docintel.pipeline.api.StoredResult;

public final class Neo4jWriterPlugin extends com.core.docintel.pipeline.api.AbstractPlugin implements PreflightPlugin, ManagedPlugin, ConfigurablePlugin {
    public Neo4jWriterPlugin(com.core.docintel.pipeline.api.PluginMessages messages){super(messages); writer=new Neo4jWriterService();}
    Neo4jWriterPlugin(WriterService service,com.core.docintel.pipeline.api.PluginMessages messages){super(messages);this.writer=service;}
    private static final PluginManifest MANIFEST = new PluginManifest(
            "neo4j-writer", "Neo4j Embedding Writer", "1.0.0", "1.0",
            "com.core.docintel.plugins.Neo4jWriterPlugin", "EmbeddingCollection", "StoredResult");
    private WriterService writer;

    public Neo4jWriterPlugin() { this(new Neo4jWriterService()); }

    Neo4jWriterPlugin(WriterService writer) { this.writer = writer; }

    @Override public PluginManifest manifest() { return MANIFEST; }
    @Override public void configure(java.util.Map<String,Object> c){writer=new Neo4jWriterService(new Neo4jConfig(
            value(c,"NEO4J_URI","bolt://localhost:7687"),value(c,"NEO4J_USERNAME","neo4j"),value(c,"NEO4J_PASSWORD",null),
            value(c,"NEO4J_DATABASE","neo4j"),value(c,"NEO4J_VECTOR_INDEX","document_embedding_idx"),Integer.parseInt(value(c,"NEO4J_VECTOR_DIMENSIONS","768"))));}
    @Override public void preflight() { writer.preflight(null); }
    @Override public void preflight(PreflightContext context) {
        writer.preflight(context.get(PreflightContext.EMBEDDING_DIMENSIONS, Integer.class));
    }

    @Override public Object execute(Object input) {
        if (!(input instanceof EmbeddingCollection collection)) throw new IllegalArgumentException("Neo4j writer expects an EmbeddingCollection.");
        emit("writing.started",java.util.Map.of("chunkCount",collection.embeddings().size()));
        int stored=writer.store(collection);
        emit("writing.completed",java.util.Map.of("storedCount",stored));
        progress(stored,(long)stored,"chunks");
        return new StoredResult(stored);
    }

    @Override public void close() { writer.close(); }

    interface WriterService {
        void preflight(Integer embeddingDimensions);
        int store(EmbeddingCollection collection);
        void close();
    }

    private static final class Neo4jWriterService implements WriterService {
        private Neo4jChunkRepository repository;
        private final Neo4jConfig config;
        private Neo4jWriterService(){this(null);}
        private Neo4jWriterService(Neo4jConfig config){this.config=config;}

        private Neo4jChunkRepository repository() {
            if (repository == null) repository = new Neo4jChunkRepository(config==null?Neo4jConfig.fromEnvironment():config);
            return repository;
        }

        @Override public void preflight(Integer embeddingDimensions) { repository().preflight(embeddingDimensions); }
        @Override public int store(EmbeddingCollection collection) { return repository().store(collection); }
        @Override public void close() { if (repository != null) repository.close(); }
    }
    private static String value(java.util.Map<String,Object> c,String key,String fallback){Object value=c.get(key);return value==null?fallback:value.toString();}
}
