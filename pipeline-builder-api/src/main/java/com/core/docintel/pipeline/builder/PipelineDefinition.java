package com.core.docintel.pipeline.builder;

import io.micronaut.serde.annotation.Serdeable;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;

@Serdeable
@JsonInclude(JsonInclude.Include.ALWAYS)
public record PipelineDefinition(String name, PipelineType type, int schemaVersion,
                                 List<PipelineNode> nodes, List<PipelineEdge> edges,
                                 Map<PipelineType, PipelineWorkflow> workflows, Map<String,Object> capabilities) {
    public PipelineDefinition(String name,PipelineType type,int schemaVersion,List<PipelineNode> nodes,List<PipelineEdge> edges,Map<PipelineType,PipelineWorkflow> workflows){this(name,type,schemaVersion,nodes,edges,workflows,null);}
    public PipelineDefinition(String name, PipelineType type, int schemaVersion, List<PipelineNode> nodes, List<PipelineEdge> edges) {
        this(name,type,schemaVersion,nodes,edges,null);
    }
    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean isProject(){return type==PipelineType.RAG_PROJECT;}
    public boolean hasWorkflow(PipelineType workflow){
        if(!isProject())return type==workflow;
        PipelineWorkflow graph=workflows==null?null:workflows.get(workflow);
        return graph!=null&&graph.nodes()!=null&&!graph.nodes().isEmpty();
    }
    public PipelineDefinition workflow(PipelineType workflow){
        if(workflow==null||workflow==PipelineType.RAG_PROJECT)throw new IllegalArgumentException("Choose ingestion or query workflow.");
        if(!hasWorkflow(workflow))throw new IllegalArgumentException("Project has no "+workflow+" workflow.");
        if(!isProject())return this;
        PipelineWorkflow graph=workflows.get(workflow);
        return new PipelineDefinition(name,workflow,1,graph.nodes(),graph.edges(),null,CapabilitySettings.forWorkflow(capabilities,workflow));
    }
    public List<PipelineNode> allNodes(){
        if(!isProject())return nodes==null?List.of():nodes;
        return workflows==null?List.of():workflows.values().stream().filter(java.util.Objects::nonNull)
                .filter(graph->graph.nodes()!=null).flatMap(graph->graph.nodes().stream()).toList();
    }
}
