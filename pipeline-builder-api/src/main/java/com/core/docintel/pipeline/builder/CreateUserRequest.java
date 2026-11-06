package com.core.docintel.pipeline.builder;

import io.micronaut.serde.annotation.Serdeable;

@Serdeable
public record CreateUserRequest(String username,String password,String role) {}
