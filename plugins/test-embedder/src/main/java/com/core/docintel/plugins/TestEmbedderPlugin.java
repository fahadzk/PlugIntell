package com.core.docintel.plugins;

import com.core.docintel.pipeline.api.ParallelSafePlugin;
import com.core.docintel.pipeline.api.PluginManifest;
import com.core.docintel.pipeline.api.EmbeddedChunk;
import com.core.docintel.pipeline.api.EmbeddingCollection;
import com.core.docintel.pipeline.api.ChunkCollection;
import com.core.docintel.pipeline.api.TextChunk;
import java.util.ArrayList;
import java.util.List;

public final class TestEmbedderPlugin extends com.core.docintel.pipeline.api.AbstractPlugin implements ParallelSafePlugin {
    public TestEmbedderPlugin(com.core.docintel.pipeline.api.PluginMessages messages){super(messages);}
    public TestEmbedderPlugin(){}
    private static final PluginManifest MANIFEST = new PluginManifest("test-embedder", "Deterministic Test Embedder", "1.0.0", "1.0", "com.core.docintel.plugins.TestEmbedderPlugin", "ChunkCollection", "EmbeddingCollection");
    public PluginManifest manifest() { return MANIFEST; }
    public Object execute(Object input) {
        if (!(input instanceof ChunkCollection chunks)) throw new IllegalArgumentException("test-embedder expects a ChunkCollection.");
        List<EmbeddedChunk> vectors = new ArrayList<>();
        for (TextChunk chunk : chunks.chunks()) {
            String text = chunk.text();
            int hash = text.hashCode();
            vectors.add(new EmbeddedChunk(chunk, List.of((hash & 0xff) / 255.0f, ((hash >>> 8) & 0xff) / 255.0f, ((hash >>> 16) & 0xff) / 255.0f)));
        }
        progress(vectors.size(),chunks.chunks().size(),"chunks");
        return new EmbeddingCollection(vectors);
    }
}
