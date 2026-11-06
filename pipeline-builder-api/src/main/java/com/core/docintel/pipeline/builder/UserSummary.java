package com.core.docintel.pipeline.builder;

import io.micronaut.serde.annotation.Serdeable;

@Serdeable
public record UserSummary(String id,String username,String role,boolean enabled,String createdAt) {}
