package com.core.docintel.pipeline.builder;

import io.micronaut.serde.annotation.Serdeable;

@Serdeable
public record ProjectBuildRequest(String pipelineId, int version, String projectName,
                                  String destination, String outputFormat) {}
