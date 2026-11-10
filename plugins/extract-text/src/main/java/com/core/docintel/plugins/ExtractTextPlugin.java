package com.core.docintel.plugins;

import com.core.docintel.pipeline.api.Plugin;
import com.core.docintel.pipeline.api.PluginManifest;
import com.core.docintel.pipeline.api.TextDocument;
import com.core.docintel.pipeline.api.TextPage;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public final class ExtractTextPlugin extends com.core.docintel.pipeline.api.AbstractPlugin implements Plugin {
    public ExtractTextPlugin(com.core.docintel.pipeline.api.PluginMessages messages){super(messages);}
    public ExtractTextPlugin(){}
    private static final PluginManifest MANIFEST = new PluginManifest("extract-text", "Text Extractor", "1.0.0", "1.0", "com.core.docintel.plugins.ExtractTextPlugin", "File", "TextDocument");

    public PluginManifest manifest() {
        return MANIFEST;
    }

    public Object execute(Object input) {
        if (!(input instanceof File file))
            throw new IllegalArgumentException("extract-text expects a java.io.File input.");
        try {
            emit("parsing.started",java.util.Map.of());
            String text = Files.readString(file.toPath(), StandardCharsets.UTF_8);
            String id = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(file.toPath().toAbsolutePath().normalize().toString().getBytes(StandardCharsets.UTF_8)));
            emit("parsing.completed",java.util.Map.of("pageCount",1));
            return new TextDocument(id, file.getName(), java.util.List.of(new TextPage(1, text)));
        } catch (IOException e) {
            throw new IllegalStateException("Could not read input file: " + file, e);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable.", e);
        }
    }
}
