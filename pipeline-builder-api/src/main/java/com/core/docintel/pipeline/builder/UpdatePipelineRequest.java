package com.core.docintel.pipeline.builder;

import io.micronaut.serde.annotation.Serdeable;

@Serdeable
public record UpdatePipelineRequest(long expectedRevision, PipelineDefinition definition) {}
