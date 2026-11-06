package com.core.docintel.pipeline.builder;

import io.micronaut.serde.annotation.Serdeable;
import java.util.List;

@Serdeable
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
public record PipelineWorkflow(List<PipelineNode> nodes, List<PipelineEdge> edges) {}
