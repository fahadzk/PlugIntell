package com.core.docintel.plugins;

import com.core.docintel.pipeline.api.Plugin;
import com.core.docintel.pipeline.api.PluginManifest;
import java.util.ArrayList;
import java.util.List;

public final class StaticSplitterPlugin implements Plugin {
    private static final PluginManifest MANIFEST = new PluginManifest(
            "static-splitter", "Static Character Splitter", "1.0.0", "1.0",
            "com.core.docintel.plugins.StaticSplitterPlugin", "TextDocument", "SegmentCollection");
    private static final int SEGMENT_SIZE = 80;

    public PluginManifest manifest() {
        return MANIFEST;
    }

    public Object execute(Object input) {
        if (!(input instanceof String text)) {
            throw new IllegalArgumentException("static-splitter expects text.");
        }
        List<String> segments = new ArrayList<>();
        for (int start = 0; start < text.length(); start += SEGMENT_SIZE) {
            segments.add(text.substring(start, Math.min(start + SEGMENT_SIZE, text.length())));
        }
        return segments;
    }
}
