package com.core.docintel.pipeline.builder;

import io.micronaut.serde.annotation.Serdeable;
import java.util.List;

@Serdeable
public record PluginInfo(String id, String name, String version, String apiVersion,
                         String entrypoint, String artifactId, String inputType,
                         String outputType, String description, List<String> capabilities,
                         List<ConfigOption> configuration) {}
