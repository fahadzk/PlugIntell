package com.core.docintel.pipeline.builder;

import io.micronaut.serde.annotation.Serdeable;

@Serdeable
public enum ExecutionStatus { QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLING, CANCELLED }
