package com.core.docintel.plugins;

import com.core.docintel.pipeline.api.ConfigurablePlugin;
import com.core.docintel.pipeline.api.ParallelSafePlugin;
import com.core.docintel.pipeline.api.PluginManifest;
import com.core.docintel.pipeline.api.ChunkCollection;
import com.core.docintel.pipeline.api.TextChunk;
import com.core.docintel.pipeline.api.TextDocument;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

public final class StaticSplitterPlugin implements ConfigurablePlugin, ParallelSafePlugin {
    private static final PluginManifest MANIFEST = new PluginManifest(
            "static-splitter", "Static Character Splitter", "1.0.0", "1.0",
            "com.core.docintel.plugins.StaticSplitterPlugin", "TextDocument", "ChunkCollection");
    private int chunkSize;

    public StaticSplitterPlugin() {
        this(Integer.parseInt(System.getenv().getOrDefault("PLUGIN_STATIC_SPLITTER_CHUNK_SIZE",
                System.getenv().getOrDefault("PLUGIN_STATIC_SPLITTER_SEGMENT_SIZE", "80"))));
    }

    public StaticSplitterPlugin(int chunkSize) {
        if (chunkSize < 1) throw new IllegalArgumentException("Chunk size must be positive.");
        this.chunkSize = chunkSize;
    }

    public PluginManifest manifest() {
        return MANIFEST;
    }

    @Override public void configure(java.util.Map<String,Object> configuration){Object configured=configuration.getOrDefault("PLUGIN_STATIC_SPLITTER_CHUNK_SIZE",configuration.getOrDefault("PLUGIN_STATIC_SPLITTER_SEGMENT_SIZE",Integer.toString(chunkSize)));int value=Integer.parseInt(configured.toString());if(value<1)throw new IllegalArgumentException("Chunk size must be positive.");chunkSize=value;}

    public Object execute(Object input) {
        if (!(input instanceof TextDocument document)) {
            throw new IllegalArgumentException("static-splitter expects a TextDocument.");
        }
        List<TextChunk> chunks = new java.util.ArrayList<>();
        int ordinal = 0;
        for (var page : document.pages()) {
            String text = page.text();
            for (int start = 0; start < text.length(); start += chunkSize) {
                String chunkText = text.substring(start, Math.min(start + chunkSize, text.length()));
                chunks.add(new TextChunk(digest(document.documentId() + "\n" + page.pageNumber() + "\n" + ordinal + "\n" + chunkText),
                        document.documentId(), document.sourceName(), page.pageNumber(), ordinal++, chunkText));
            }
        }
        return new ChunkCollection(document.documentId(), chunks);
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable.", e);
        }
    }
}
