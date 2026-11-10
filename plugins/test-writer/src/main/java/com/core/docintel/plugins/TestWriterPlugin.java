package com.core.docintel.plugins;

import com.core.docintel.pipeline.api.Plugin;
import com.core.docintel.pipeline.api.PluginManifest;
import com.core.docintel.pipeline.api.EmbeddingCollection;
import com.core.docintel.pipeline.api.StoredResult;

public final class TestWriterPlugin extends com.core.docintel.pipeline.api.AbstractPlugin implements Plugin {
    public TestWriterPlugin(com.core.docintel.pipeline.api.PluginMessages messages){super(messages);}
    public TestWriterPlugin(){}
    private static final PluginManifest MANIFEST = new PluginManifest("test-writer", "Test Result Writer", "1.0.0", "1.0", "com.core.docintel.plugins.TestWriterPlugin", "EmbeddingCollection", "StoredResult");
    public PluginManifest manifest() { return MANIFEST; }
    public Object execute(Object input) {
        if (!(input instanceof EmbeddingCollection embeddings)) throw new IllegalArgumentException("test-writer expects an EmbeddingCollection.");
        System.out.println("Stored " + embeddings.embeddings().size() + " embeddings.");
        emit("writing.completed",java.util.Map.of("storedCount",embeddings.embeddings().size()));
        return new StoredResult(embeddings.embeddings().size());
    }
}
