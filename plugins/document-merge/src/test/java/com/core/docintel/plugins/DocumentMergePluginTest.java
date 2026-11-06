package com.core.docintel.plugins;

import com.core.docintel.pipeline.api.TextDocument;
import com.core.docintel.pipeline.api.TextPage;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class DocumentMergePluginTest {
    @Test void mergesBranchesAndRenumbersPagesInInputOrder() {
        DocumentMergePlugin plugin = new DocumentMergePlugin();
        TextDocument merged = (TextDocument) plugin.executeInputs(List.of(
                new TextDocument("a", "alpha.pdf", List.of(new TextPage(3, "first"), new TextPage(4, "second"))),
                new TextDocument("b", "beta.pdf", List.of(new TextPage(1, "third")))));
        assertEquals("alpha.pdf + beta.pdf", merged.sourceName());
        assertEquals(List.of(1, 2, 3), merged.pages().stream().map(TextPage::pageNumber).toList());
        assertEquals("first\nsecond\nthird", merged.text());
        assertEquals(64, merged.documentId().length());
    }

    @Test void rejectsInvalidFanInValues() {
        DocumentMergePlugin plugin = new DocumentMergePlugin();
        assertThrows(IllegalArgumentException.class, () -> plugin.executeInputs(List.of("not a document")));
        assertThrows(IllegalArgumentException.class, () -> plugin.executeInputs(List.of()));
    }
}
