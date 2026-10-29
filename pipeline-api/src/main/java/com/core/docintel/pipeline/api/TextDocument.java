package com.core.docintel.pipeline.api;

import java.util.List;

public record TextDocument(String documentId, String sourceName, List<TextPage> pages) {
    public TextDocument {
        if (documentId == null || documentId.isBlank()) throw new IllegalArgumentException("Document ID is required.");
        if (sourceName == null || sourceName.isBlank()) throw new IllegalArgumentException("Source name is required.");
        pages = List.copyOf(pages);
    }

    public String text() {
        return pages.stream().map(TextPage::text).collect(java.util.stream.Collectors.joining("\n"));
    }
}
