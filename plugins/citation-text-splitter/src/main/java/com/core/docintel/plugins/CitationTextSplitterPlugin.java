package com.core.docintel.plugins;

import com.core.docintel.pipeline.api.ChunkCollection;
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

public final class CitationTextSplitterPlugin implements Plugin {
    private static final PluginManifest MANIFEST = new PluginManifest(
            "citation-text-splitter", "Citation-Preserving Text Splitter", "1.0.0", "1.0",
            "com.core.docintel.plugins.CitationTextSplitterPlugin", "TextDocument", "ChunkCollection");
    private final int chunkSize;
    private final int overlap;

    public CitationTextSplitterPlugin() {
        this(Integer.getInteger("plugin.text-splitter.chunk-size", 1000),
                Integer.getInteger("plugin.text-splitter.overlap", 120));
    }

    public CitationTextSplitterPlugin(int chunkSize, int overlap) {
        if (chunkSize <= 0) throw new IllegalArgumentException("Chunk size must be positive.");
        if (overlap < 0 || overlap >= chunkSize) throw new IllegalArgumentException("Overlap must be non-negative and smaller than chunk size.");
        this.chunkSize = chunkSize;
        this.overlap = overlap;
    }

    @Override public PluginManifest manifest() { return MANIFEST; }

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
