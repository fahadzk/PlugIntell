package com.core.docintel.pipeline.builder;

import io.micronaut.serde.annotation.Serdeable;

@Serdeable
public record UploadedFile(String filePath,String originalName,long sizeBytes) {}
