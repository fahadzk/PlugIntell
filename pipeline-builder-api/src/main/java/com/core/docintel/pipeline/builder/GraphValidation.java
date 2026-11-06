package com.core.docintel.pipeline.builder;

import io.micronaut.serde.annotation.Serdeable;
import java.util.List;

@Serdeable
public record GraphValidation(boolean valid, List<String> errors, List<String> executionOrder) {}
