package com.core.docintel.pipeline.builder;

import io.micronaut.serde.annotation.Serdeable;
import java.util.List;
import java.util.Map;

@Serdeable
public record PluginDescriptor(String id, String name, String version, String category,
                               String description, String apiVersion, List<String> capabilities,
                               List<PluginPort> inputs, List<PluginPort> outputs,
                               Map<String, Object> configurationSchema,
                               Map<String, Object> uiSchema) {}
