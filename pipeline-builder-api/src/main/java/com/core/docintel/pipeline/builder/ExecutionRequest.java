package com.core.docintel.pipeline.builder;

import io.micronaut.serde.annotation.Serdeable;

@Serdeable
public record ExecutionRequest(String pipelineId, int version, String query, String filePath,
                               Integer topK, Boolean includeCitations) {}
