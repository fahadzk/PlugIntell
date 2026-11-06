package com.core.docintel.pipeline.api;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/** A plugin that extracts and splits a document while returning provenance-aware chunks. */
public interface DocumentSplitterPlugin extends Plugin {

    /** Parse and split the given document, returning chunk text in pipeline order. */
    List<String> split(File document);

    @Override
    default Object execute(Object input) {
        if (!(input instanceof File file)) {
            throw new IllegalArgumentException("Document splitter expects a java.io.File input.");
        }
        List<String> values = split(file);
        String sourceName = file.getName().isBlank() ? file.getPath() : file.getName();
        String documentId = digest(file.getAbsoluteFile().toPath().normalize().toString());
        List<TextChunk> chunks = new ArrayList<>(values.size());
        for (int i = 0; i < values.size(); i++) {
            String text = values.get(i);
            if (text == null || text.isBlank()) continue;
            chunks.add(new TextChunk(digest(documentId + "\n1\n" + i + "\n" + text), documentId, sourceName, 1, i, text));
        }
        return new ChunkCollection(documentId, chunks);
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable.", e);
        }
    }
}
