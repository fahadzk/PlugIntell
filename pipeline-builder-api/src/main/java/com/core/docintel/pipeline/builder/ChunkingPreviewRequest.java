package com.core.docintel.pipeline.builder;

import io.micronaut.serde.annotation.Serdeable;
import java.util.Map;

@Serdeable
public record ChunkingPreviewRequest(String pluginId, String pluginVersion, String text,
                                     Map<String,Object> config) {}
