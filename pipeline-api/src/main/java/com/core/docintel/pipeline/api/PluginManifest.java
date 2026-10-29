package com.core.docintel.pipeline.api;

public record PluginManifest(String id, String name, String version, String apiVersion,
                             String entrypoint, String inputType, String outputType) {}
