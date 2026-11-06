package com.core.docintel.pipeline.builder;

import io.micronaut.serde.annotation.Serdeable;

@Serdeable
public record PluginPort(String id, String label, String dataType, String cardinality) {}
