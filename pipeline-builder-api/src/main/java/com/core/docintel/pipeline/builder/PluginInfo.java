package com.core.docintel.pipeline.builder;

import io.micronaut.serde.annotation.Serdeable;
import java.util.List;

@Serdeable
public record PluginInfo(String id, String name, String version, String apiVersion,
                         String entrypoint, String artifactId, String inputType,
                         String outputType, String description, List<String> capabilities,
                         List<ConfigOption> configuration, List<PipelineType> workflows,
                         java.util.List<java.util.Map<String,Object>> configurationConditions) {
    public PluginInfo { configurationConditions=configurationConditions==null?java.util.List.of():java.util.List.copyOf(configurationConditions); }
    public PluginInfo(String id,String name,String version,String apiVersion,String entrypoint,String artifactId,String inputType,String outputType,String description,List<String> capabilities,List<ConfigOption> configuration,List<PipelineType> workflows){
        this(id,name,version,apiVersion,entrypoint,artifactId,inputType,outputType,description,capabilities,configuration,workflows,java.util.List.of());
    }
    public PluginInfo(String id,String name,String version,String apiVersion,String entrypoint,String artifactId,String inputType,String outputType,String description,List<String> capabilities,List<ConfigOption> configuration){
        this(id,name,version,apiVersion,entrypoint,artifactId,inputType,outputType,description,capabilities,configuration,List.of(PipelineType.DOCUMENT_INGESTION,PipelineType.QUERY_PROCESSING));
    }
}
