package com.core.docintel.pipeline.builder;
import io.micronaut.serde.annotation.Serdeable;
@Serdeable
public record ConnectionProfileRevisionRequest(long expectedRevision) {}
