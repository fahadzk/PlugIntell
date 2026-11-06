package com.core.docintel.pipeline.builder;

import io.micronaut.serde.annotation.Serdeable;
import java.time.Instant;

@Serdeable
public record ExecutionEvent(long sequence, Instant timestamp, String nodeId,
                             String status, String message, long durationMillis) {}
