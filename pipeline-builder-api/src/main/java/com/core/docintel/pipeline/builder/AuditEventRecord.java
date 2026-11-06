package com.core.docintel.pipeline.builder;

import io.micronaut.serde.annotation.Serdeable;

@Serdeable
public record AuditEventRecord(long id,String actor,String role,String method,String path,String outcome,String requestId,String createdAt) {}
