package com.core.docintel.plugins;

import com.core.docintel.pipeline.api.FanInPlugin;
import com.core.docintel.pipeline.api.PluginManifest;
import com.core.docintel.pipeline.api.TextDocument;
import com.core.docintel.pipeline.api.TextPage;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.TreeSet;

/** Sequential fan-in node for merging parsed document branches before chunking. */
public final class DocumentMergePlugin implements FanInPlugin {
    private static final PluginManifest MANIFEST = new PluginManifest("document-merge", "Document Merge", "1.0.0", "1.0",
            "com.core.docintel.plugins.DocumentMergePlugin", "TextDocument", "TextDocument");

    @Override public PluginManifest manifest() { return MANIFEST; }

    @Override public Object executeInputs(List<Object> inputs) {
        if (inputs == null || inputs.isEmpty()) throw new IllegalArgumentException("Document merge requires at least one input document.");
        List<TextDocument> documents = new ArrayList<>(inputs.size());
        for (Object input : inputs) {
            if (!(input instanceof TextDocument document)) throw new IllegalArgumentException("Document merge accepts only TextDocument inputs.");
            documents.add(document);
        }
        List<TextPage> pages = new ArrayList<>();
        TreeSet<String> sources = new TreeSet<>();
        List<String> ids = new ArrayList<>();
        int pageNumber = 1;
        for (TextDocument document : documents) {
            sources.add(document.sourceName());
            ids.add(document.documentId());
            for (TextPage page : document.pages()) pages.add(new TextPage(pageNumber++, page.text()));
        }
        String sourceName = String.join(" + ", sources);
        return new TextDocument(digest(String.join("|", ids)), sourceName, pages);
    }

    private static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException unavailable) { throw new IllegalStateException("SHA-256 is unavailable.", unavailable); }
    }
}
