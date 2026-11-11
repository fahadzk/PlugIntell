package com.core.docintel.pipeline.api;

/** Host-controlled scope. Never construct this from prompts, query filters or node configuration. */
public record PluginExecutionContext(String namespace, String accessScope, String runId,
                                     String pipelineId, Integer pipelineVersion, String nodeId) {
    public PluginExecutionContext {
        if (namespace == null || !namespace.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,119}")
                || accessScope == null || !accessScope.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,119}"))
            throw new IllegalStateException("The host must set PLUGINTELL_GRAPH_NAMESPACE and PLUGINTELL_GRAPH_ACCESS_SCOPE to explicit scope identifiers.");
    }
    public static PluginExecutionContext fromHost(String run, String pipeline, Integer version, String node) {
        return new PluginExecutionContext(System.getenv("PLUGINTELL_GRAPH_NAMESPACE"),
                System.getenv("PLUGINTELL_GRAPH_ACCESS_SCOPE"), run, pipeline, version, node);
    }
}
