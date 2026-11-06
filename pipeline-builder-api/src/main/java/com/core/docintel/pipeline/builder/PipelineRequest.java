package com.core.docintel.pipeline.builder;

import io.micronaut.serde.annotation.Serdeable;
import java.util.List;
import java.util.Map;

@Serdeable
public record PipelineRequest(String projectName, List<String> pluginIds,
                              String outputFormat, Map<String, String> configuration) {}
