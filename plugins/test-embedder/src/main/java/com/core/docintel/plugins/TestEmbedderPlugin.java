package com.core.docintel.plugins;

import com.core.docintel.pipeline.api.Plugin;
import com.core.docintel.pipeline.api.PluginManifest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class TestEmbedderPlugin implements Plugin {
    private static final PluginManifest MANIFEST = new PluginManifest("test-embedder", "Deterministic Test Embedder", "1.0.0", "1.0", "com.core.docintel.plugins.TestEmbedderPlugin", "SegmentCollection", "EmbeddingCollection");
    public PluginManifest manifest() { return MANIFEST; }
    public Object execute(Object input) {
        if (!(input instanceof List<?> segments)) throw new IllegalArgumentException("test-embedder expects a list of text segments.");
        List<Map<String, Object>> vectors = new ArrayList<>();
        for (Object value : segments) {
            String text = String.valueOf(value);
            int hash = text.hashCode();
            vectors.add(Map.of("text", text, "vector", List.of((hash & 0xff) / 255.0, ((hash >>> 8) & 0xff) / 255.0, ((hash >>> 16) & 0xff) / 255.0)));
        }
        return vectors;
    }
}
