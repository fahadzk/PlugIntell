package com.core.docintel.plugins;

import com.core.docintel.pipeline.api.Plugin;
import com.core.docintel.pipeline.api.PluginManifest;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public final class ExtractTextPlugin implements Plugin {
    private static final PluginManifest MANIFEST = new PluginManifest("extract-text", "Text Extractor", "1.0.0", "1.0", "com.core.docintel.plugins.ExtractTextPlugin", "File", "TextDocument");

    public PluginManifest manifest() {
        return MANIFEST;
    }

    public Object execute(Object input) {
        if (!(input instanceof File file))
            throw new IllegalArgumentException("extract-text expects a java.io.File input.");
        try {
            return Files.readString(file.toPath(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Could not read input file: " + file, e);
        }
    }
}
