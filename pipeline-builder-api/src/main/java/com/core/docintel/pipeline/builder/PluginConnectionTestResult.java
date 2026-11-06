package com.core.docintel.pipeline.builder;

import io.micronaut.serde.annotation.Serdeable;

@Serdeable
public record PluginConnectionTestResult(boolean success, boolean connectionTested, String message, long durationMillis) { }
