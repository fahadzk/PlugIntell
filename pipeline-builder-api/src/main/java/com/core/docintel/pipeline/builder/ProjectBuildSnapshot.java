package com.core.docintel.pipeline.builder;

import io.micronaut.serde.annotation.Serdeable;
import java.time.Instant;
import java.util.List;

@Serdeable
public record ProjectBuildSnapshot(String id, String pipelineId, int pipelineVersion,
                                   String projectName, String destination, String outputFormat,
                                   String status, int progress, List<String> logs,
                                   String error, String artifactName, Instant createdAt,
                                   Instant updatedAt) {}
