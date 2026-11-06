package com.core.docintel.pipeline.builder;

import io.micronaut.serde.annotation.Serdeable;
import java.time.Instant;

@Serdeable
public record PipelineRecord(String id, long revision, Integer publishedVersion,
                             Instant createdAt, Instant updatedAt,
                             PipelineDefinition definition) {}
