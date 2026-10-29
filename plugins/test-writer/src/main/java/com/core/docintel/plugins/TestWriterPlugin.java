package com.core.docintel.plugins;

import com.core.docintel.pipeline.api.Plugin;
import com.core.docintel.pipeline.api.PluginManifest;
import java.util.List;
import java.util.Map;

public final class TestWriterPlugin implements Plugin {
    private static final PluginManifest MANIFEST = new PluginManifest("test-writer", "Test Result Writer", "1.0.0", "1.0", "com.core.docintel.plugins.TestWriterPlugin", "EmbeddingCollection", "StoredResult");
    public PluginManifest manifest() { return MANIFEST; }
    public Object execute(Object input) {
        if (!(input instanceof List<?> embeddings)) throw new IllegalArgumentException("test-writer expects an embedding list.");
        System.out.println("Stored " + embeddings.size() + " embeddings.");
        return Map.of("storedCount", embeddings.size(), "embeddings", List.copyOf(embeddings));
    }
}
