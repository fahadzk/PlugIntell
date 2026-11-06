package com.core.docintel.pipeline.builder;

import io.micronaut.serde.annotation.Serdeable;

@Serdeable
public record AuthToken(String accessToken,String tokenType,String username,String role,String expiresAt) {}
