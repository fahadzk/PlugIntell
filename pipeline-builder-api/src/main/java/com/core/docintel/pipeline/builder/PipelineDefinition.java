package com.core.docintel.pipeline.builder;

import io.micronaut.serde.annotation.Serdeable;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

@Serdeable
@JsonInclude(JsonInclude.Include.ALWAYS)
public record PipelineDefinition(String name, PipelineType type, int schemaVersion,
                                 List<PipelineNode> nodes, List<PipelineEdge> edges) {}
