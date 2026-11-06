package com.core.docintel.pipeline.builder;

import io.micronaut.serde.annotation.Serdeable;
import java.util.Map;

@Serdeable
public record ConnectionPluginSettings(String pluginVersion, Map<String, Object> config) { }
