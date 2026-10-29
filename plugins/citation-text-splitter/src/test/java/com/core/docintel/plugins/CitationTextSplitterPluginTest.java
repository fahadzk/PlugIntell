package com.core.docintel.plugins;

import com.core.docintel.pipeline.api.ChunkCollection;
import com.core.docintel.pipeline.api.TextDocument;
import com.core.docintel.pipeline.api.TextPage;
import org.junit.jupiter.api.Test;

import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class CitationTextSplitterPluginTest {
    @Test void splitsAndRetainsPageAndSourceForEveryChunk() {
        var document = new TextDocument("doc-id", "manual.pdf", List.of(
                new TextPage(1, "alpha beta gamma delta epsilon"),
                new TextPage(2, "second page text")));
        ChunkCollection chunks = (ChunkCollection) new CitationTextSplitterPlugin(12, 2).execute(document);
        assertTrue(chunks.chunks().size() > 2);
        assertTrue(chunks.chunks().stream().allMatch(c -> c.sourceName().equals("manual.pdf")));
        assertTrue(chunks.chunks().stream().anyMatch(c -> c.pageNumber() == 2));
        assertTrue(chunks.chunks().stream().allMatch(c -> !c.id().isBlank()));
    }

    @Test void rejectsInvalidOverlap() {
        assertThrows(IllegalArgumentException.class, () -> new CitationTextSplitterPlugin(10, 10));
    }
}
