package com.core.docintel.pipeline.builder;

import io.micronaut.serde.annotation.Serdeable;
import java.time.Instant;
import java.util.Map;

@Serdeable
public record ExecutionSnapshot(String id, String pipelineId, int pipelineVersion,
                                ExecutionStatus status, Instant submittedAt,
                                Instant startedAt, Instant finishedAt,
                                Map<String,Object> output, String error) {}
