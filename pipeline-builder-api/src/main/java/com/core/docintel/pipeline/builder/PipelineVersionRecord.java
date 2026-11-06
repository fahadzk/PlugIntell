package com.core.docintel.pipeline.builder;

import io.micronaut.serde.annotation.Serdeable;
import java.time.Instant;

@Serdeable
public record PipelineVersionRecord(String pipelineId, int version,
                                    Instant publishedAt, PipelineDefinition definition) {}
