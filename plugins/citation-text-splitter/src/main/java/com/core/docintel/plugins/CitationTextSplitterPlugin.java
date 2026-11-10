package com.core.docintel.plugins;

import com.core.docintel.pipeline.api.ChunkCollection;
import com.core.docintel.pipeline.api.ConfigurablePlugin;
import com.core.docintel.pipeline.api.Plugin;
import com.core.docintel.pipeline.api.PluginManifest;
import com.core.docintel.pipeline.api.TextChunk;
import com.core.docintel.pipeline.api.TextDocument;
import com.core.docintel.pipeline.api.TextPage;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

public final class CitationTextSplitterPlugin extends com.core.docintel.pipeline.api.AbstractPlugin implements ConfigurablePlugin {
    public CitationTextSplitterPlugin(com.core.docintel.pipeline.api.PluginMessages messages){super(messages); chunkSize=Integer.parseInt(System.getenv().getOrDefault("PLUGIN_TEXT_SPLITTER_CHUNK_SIZE",System.getProperty("plugin.text-splitter.chunk-size","1000"))); overlap=Integer.parseInt(System.getenv().getOrDefault("PLUGIN_TEXT_SPLITTER_OVERLAP",System.getProperty("plugin.text-splitter.overlap","120"))); configure(java.util.Map.of());}
    private static final PluginManifest MANIFEST = new PluginManifest(
            "citation-text-splitter", "Citation-Preserving Text Splitter", "1.0.0", "1.0",
            "com.core.docintel.plugins.CitationTextSplitterPlugin", "TextDocument", "ChunkCollection");
    private int chunkSize;
    private int overlap;

    public CitationTextSplitterPlugin() {
        this(Integer.parseInt(System.getenv().getOrDefault("PLUGIN_TEXT_SPLITTER_CHUNK_SIZE", System.getProperty("plugin.text-splitter.chunk-size", "1000"))),
                Integer.parseInt(System.getenv().getOrDefault("PLUGIN_TEXT_SPLITTER_OVERLAP", System.getProperty("plugin.text-splitter.overlap", "120"))));
    }

    public CitationTextSplitterPlugin(int chunkSize, int overlap) {
        if (chunkSize <= 0) throw new IllegalArgumentException("Chunk size must be positive.");
        if (overlap < 0 || overlap >= chunkSize) throw new IllegalArgumentException("Overlap must be non-negative and smaller than chunk size.");
        this.chunkSize = chunkSize;
        this.overlap = overlap;
    }

    @Override public PluginManifest manifest() { return MANIFEST; }

    @Override public void configure(java.util.Map<String,Object> configuration) {
        int size = configuration.containsKey("PLUGIN_TEXT_SPLITTER_CHUNK_SIZE")
                ? Integer.parseInt(configuration.get("PLUGIN_TEXT_SPLITTER_CHUNK_SIZE").toString()) : chunkSize;
        int overlapValue = configuration.containsKey("PLUGIN_TEXT_SPLITTER_OVERLAP")
                ? Integer.parseInt(configuration.get("PLUGIN_TEXT_SPLITTER_OVERLAP").toString()) : overlap;
        if(size<=0)throw new IllegalArgumentException("Chunk size must be positive.");
        if(overlapValue<0||overlapValue>=size)throw new IllegalArgumentException("Overlap must be non-negative and smaller than chunk size.");
        chunkSize=size;overlap=overlapValue;
    }

    @Override
    public Object execute(Object input) {
        if (!(input instanceof TextDocument document)) throw new IllegalArgumentException("Citation text splitter expects a TextDocument.");
        List<TextChunk> chunks = new ArrayList<>();
        int ordinal = 0;
        for (TextPage page : document.pages()) {
            String text = page.text().strip();
            for (int start = 0; start < text.length();) {
                int end = Math.min(start + chunkSize, text.length());
                if (end < text.length()) {
                    int boundary = text.lastIndexOf(' ', end);
                    if (boundary > start + chunkSize / 2) end = boundary;
                }
                String chunkText = text.substring(start, end).strip();
                if (!chunkText.isEmpty()) {
                    String id = digest(document.documentId() + "\n" + page.pageNumber() + "\n" + ordinal + "\n" + chunkText);
                    chunks.add(new TextChunk(id, document.documentId(), document.sourceName(), page.pageNumber(), ordinal++, chunkText));
                }
                if (end >= text.length()) break;
                int next = Math.max(start + 1, end - overlap);
                while (next < end && Character.isWhitespace(text.charAt(next))) next++;
                start = next;
            }
        }
        emit("splitting.completed",java.util.Map.of("chunkCount",chunks.size()));
        return new ChunkCollection(document.documentId(), chunks);
    }

    private static String digest(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable.", e);
        }
    }
}
