package com.core.docintel.pipeline.builder;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ChunkingPreviewServiceTest {
    @TempDir Path temp;

    @Test void rejectsMissingTextAndUnknownPluginWithoutPersistingAnything() {
        var service = new ChunkingPreviewService(new PluginCatalog(temp.resolve("plugins").toString()),new GraphPipelineValidator());
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> service.preview(new ChunkingPreviewRequest("missing", "1.0.0", "", Map.of())))
                .getMessage().contains("Preview text is required"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> service.preview(new ChunkingPreviewRequest("missing", "1.0.0", "some text", Map.of())))
                .getMessage().contains("Unknown plugin ID"));
    }
}
