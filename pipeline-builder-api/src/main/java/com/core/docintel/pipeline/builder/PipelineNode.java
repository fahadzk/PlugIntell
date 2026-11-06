package com.core.docintel.pipeline.builder;

import io.micronaut.serde.annotation.Serdeable;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;

@Serdeable
@JsonInclude(JsonInclude.Include.ALWAYS)
public record PipelineNode(String id, String pluginId, String pluginVersion,
                           Map<String, Object> config,NodeExecutionPolicy executionPolicy) {
    public PipelineNode(String id,String pluginId,String pluginVersion,Map<String,Object> config){this(id,pluginId,pluginVersion,config,null);}
    public NodeExecutionPolicy effectiveExecutionPolicy(){return executionPolicy==null?NodeExecutionPolicy.defaults():executionPolicy;}
}
