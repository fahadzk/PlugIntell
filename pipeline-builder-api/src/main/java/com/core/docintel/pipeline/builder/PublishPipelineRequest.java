package com.core.docintel.pipeline.builder;

import io.micronaut.serde.annotation.Serdeable;

@Serdeable
public record PublishPipelineRequest(long expectedRevision) {}
