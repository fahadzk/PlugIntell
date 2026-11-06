package com.core.docintel.pipeline.builder;

import io.micronaut.serde.annotation.Serdeable;
import java.util.List;

@Serdeable
public record PipelineValidation(boolean valid, boolean generationSupported,
                                 List<PluginInfo> plugins, List<String> errors,
                                 List<String> connections, String generationMessage) {}
