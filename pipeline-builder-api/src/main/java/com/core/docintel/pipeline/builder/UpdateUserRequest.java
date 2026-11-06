package com.core.docintel.pipeline.builder;

import io.micronaut.serde.annotation.Serdeable;

@Serdeable
public record UpdateUserRequest(String role,Boolean enabled) {}
