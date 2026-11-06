package com.core.docintel.pipeline.builder;

import io.micronaut.serde.annotation.Serdeable;

/** Retry and timeout controls snapshotted with each pipeline node. */
@Serdeable
public record NodeExecutionPolicy(int maxRetries,long initialBackoffMillis,long maxBackoffMillis,long timeoutMillis) {
    public static NodeExecutionPolicy defaults(){return new NodeExecutionPolicy(0,250,5_000,300_000);}
}
