package com.core.docintel.pipeline.builder;

import io.micronaut.serde.annotation.Serdeable;
import java.util.List;

@Serdeable
public record ChunkingPreviewResult(String pluginId, int inputCharacters, int chunkCount,
                                    List<ChunkPreview> chunks) {
    @Serdeable
    public record ChunkPreview(int ordinal, String text, int characters) {}
}
