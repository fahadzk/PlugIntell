package com.core.docintel.pipeline.builder;

import io.micronaut.serde.annotation.Serdeable;
import java.util.Map;

@Serdeable
public record PluginConnectionTestRequest(Map<String, Object> config) { }
