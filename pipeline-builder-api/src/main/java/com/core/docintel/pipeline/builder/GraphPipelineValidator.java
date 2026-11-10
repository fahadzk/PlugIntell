package com.core.docintel.pipeline.builder;

import jakarta.inject.Singleton;
import java.util.*;

/** Validates the editable DAG model against the currently discovered, single-input/single-output plugins. */
@Singleton
public final class GraphPipelineValidator {
    public GraphValidation validate(PipelineDefinition definition, Collection<PluginInfo> availablePlugins) {
        return validate(definition,availablePlugins,false);
    }

    public GraphValidation validate(PipelineDefinition definition, Collection<PluginInfo> availablePlugins, boolean requireCompleteConfiguration) {
        List<String> errors = new ArrayList<>();
        if (definition == null) return new GraphValidation(false, List.of("Pipeline definition is required."), List.of());
        try{CapabilitySettings.validate(definition,false);}catch(IllegalArgumentException e){return new GraphValidation(false,List.of(e.getMessage()),List.of());}
        if(definition.isProject())return validateProject(definition,availablePlugins,requireCompleteConfiguration);
        if(definition.workflows()!=null&&!definition.workflows().isEmpty())errors.add("Workflow lanes require RAG_PROJECT type and schema version 2.");
        if (definition.name() == null || definition.name().isBlank()) errors.add("Pipeline name is required.");
        if (definition.type() == null) errors.add("Pipeline type is required.");
        if (definition.schemaVersion() != 1) errors.add("Unsupported pipeline schema version: " + definition.schemaVersion() + ". Supported version is 1.");
        if (definition.nodes() == null || definition.nodes().isEmpty()) errors.add("Pipeline must contain at least one node.");
        if (definition.nodes() == null || definition.edges() == null) return new GraphValidation(false, append(errors, "Nodes and edges are required."), List.of());

        Map<String, PluginInfo> plugins = new HashMap<>();
        if (availablePlugins != null) availablePlugins.forEach(plugin -> plugins.put(plugin.id(), plugin));
        Map<String, PipelineNode> nodes = new LinkedHashMap<>();
        for (PipelineNode node : definition.nodes()) {
            if (node == null || node.id() == null || node.id().isBlank()) { errors.add("Each node must have a non-empty ID."); continue; }
            if (nodes.putIfAbsent(node.id(), node) != null) { errors.add("Duplicate node ID: " + node.id() + "."); continue; }
            if (node.pluginId() == null || node.pluginId().isBlank()) { errors.add("Node '" + node.id() + "' must reference a plugin ID."); continue; }
            PluginInfo plugin = plugins.get(node.pluginId());
            if (plugin == null) { errors.add("Unknown plugin ID '" + node.pluginId() + "' on node '" + node.id() + "'."); continue; }
            if(definition.type()!=null&&!plugin.workflows().contains(definition.type()))errors.add("Plugin '"+plugin.id()+"' does not support "+definition.type()+".");
            if (node.pluginVersion() == null || !node.pluginVersion().equals(plugin.version()))
                errors.add("Node '" + node.id() + "' pins plugin version '" + node.pluginVersion() + "'; available version is '" + plugin.version() + "'.");
            validateConfiguration(node, plugin, errors, requireCompleteConfiguration);
            validateExecutionPolicy(node,errors);
        }

        Map<String, Integer> indegree = new LinkedHashMap<>();
        Map<String, List<String>> next = new LinkedHashMap<>();
        nodes.keySet().forEach(id -> { indegree.put(id, 0); next.put(id, new ArrayList<>()); });
        Set<String> seenEdges = new HashSet<>();
        for (PipelineEdge edge : definition.edges()) {
            if (edge == null || edge.from() == null || edge.to() == null) { errors.add("Each edge must define 'from' and 'to' node IDs."); continue; }
            if (!nodes.containsKey(edge.from())) { errors.add("Edge references unknown source node '" + edge.from() + "'."); continue; }
            if (!nodes.containsKey(edge.to())) { errors.add("Edge references unknown target node '" + edge.to() + "'."); continue; }
            if (edge.from().equals(edge.to())) { errors.add("Node '" + edge.from() + "' cannot connect to itself."); continue; }
            String key = edge.from() + "\u0000" + edge.to();
            if (!seenEdges.add(key)) { errors.add("Duplicate edge '" + edge.from() + "' -> '" + edge.to() + "'."); continue; }
            PipelineNode source = nodes.get(edge.from()), target = nodes.get(edge.to());
            PluginInfo sourcePlugin = plugins.get(source.pluginId()), targetPlugin = plugins.get(target.pluginId());
            if (sourcePlugin != null && targetPlugin != null && !sourcePlugin.outputType().equals(targetPlugin.inputType()))
                errors.add("Cannot connect '" + edge.to() + "': expects " + targetPlugin.inputType() + ", but '" + edge.from() + "' outputs " + sourcePlugin.outputType() + ".");
            next.get(edge.from()).add(edge.to());
            indegree.compute(edge.to(), (id, degree) -> degree + 1);
        }

        long rootCount=indegree.values().stream().filter(degree->degree==0).count();
        if(nodes.size()>1 && rootCount!=1) errors.add("Pipeline graph must have exactly one root node; found "+rootCount+".");
        if(rootCount==1&&definition.type()!=null){
            String expectedInput=definition.type()==PipelineType.DOCUMENT_INGESTION?"File":"SearchRequest";
            for(var entry:indegree.entrySet())if(entry.getValue()==0){
                PipelineNode root=nodes.get(entry.getKey());PluginInfo rootPlugin=plugins.get(root.pluginId());
                if(rootPlugin!=null&&!expectedInput.equals(rootPlugin.inputType()))
                    errors.add("The root node of a "+definition.type()+" pipeline must accept "+expectedInput+" input, but '"+root.id()+"' accepts "+rootPlugin.inputType()+".");
            }
        }
        indegree.forEach((id,degree)->{
            if(degree>1){PipelineNode target=nodes.get(id);PluginInfo plugin=target==null?null:plugins.get(target.pluginId());
                if(plugin==null||!plugin.capabilities().contains("fan-in"))errors.add("Node '"+id+"' has multiple incoming edges but its plugin does not declare the fan-in capability.");}
        });

        PriorityQueue<String> ready = new PriorityQueue<>();
        indegree.forEach((id, degree) -> { if (degree == 0) ready.add(id); });
        List<String> order = new ArrayList<>();
        while (!ready.isEmpty()) {
            String id = ready.remove(); order.add(id);
            for (String target : next.get(id)) if (indegree.compute(target, (key, degree) -> degree - 1) == 0) ready.add(target);
        }
        if (order.size() != nodes.size()) errors.add("Pipeline graph contains a cycle; only directed acyclic graphs are supported.");
        if (!nodes.isEmpty() && definition.edges().isEmpty() && nodes.size() > 1) errors.add("A pipeline with multiple nodes must contain connecting edges.");
        return new GraphValidation(errors.isEmpty(), List.copyOf(errors), errors.isEmpty() ? List.copyOf(order) : List.of());
    }

    private GraphValidation validateProject(PipelineDefinition definition,Collection<PluginInfo> plugins,boolean complete){
        List<String> errors=new ArrayList<>(),order=new ArrayList<>();
        if(definition.name()==null||definition.name().isBlank())errors.add("Project name is required.");
        if(definition.schemaVersion()!=2)errors.add("RAG projects require schema version 2.");
        if(definition.nodes()!=null&&!definition.nodes().isEmpty()||definition.edges()!=null&&!definition.edges().isEmpty())errors.add("Project nodes and edges must be inside their workflow lanes.");
        if(definition.workflows()==null||definition.workflows().isEmpty())errors.add("Project workflows are required.");
        else for(var entry:definition.workflows().entrySet()){
            PipelineType type=entry.getKey();PipelineWorkflow graph=entry.getValue();
            if(type==null||type==PipelineType.RAG_PROJECT){errors.add("Only ingestion and query workflow lanes are supported.");continue;}
            if(graph==null||graph.nodes()==null||graph.edges()==null){errors.add(type+": nodes and edges are required.");continue;}
            if(graph.nodes().isEmpty()&&graph.edges().isEmpty())continue;
            GraphValidation result=validate(new PipelineDefinition(definition.name(),type,1,graph.nodes(),graph.edges()),plugins,complete);
            result.errors().forEach(error->errors.add(type+": "+error));
            result.executionOrder().forEach(id->order.add(type+"/"+id));
        }
        if(!definition.hasWorkflow(PipelineType.DOCUMENT_INGESTION)&&!definition.hasWorkflow(PipelineType.QUERY_PROCESSING))errors.add("Add at least one workflow to the project.");
        return new GraphValidation(errors.isEmpty(),List.copyOf(errors),errors.isEmpty()?List.copyOf(order):List.of());
    }

    public GraphValidation validatePluginConfiguration(String pluginId,String pluginVersion,Map<String,Object> configuration,
                                                       Collection<PluginInfo> availablePlugins,boolean requireCompleteConfiguration){
        List<String> errors=new ArrayList<>();
        PluginInfo plugin=availablePlugins==null?null:availablePlugins.stream().filter(value->value.id().equals(pluginId)).findFirst().orElse(null);
        if(plugin==null)errors.add("Unknown plugin ID '"+pluginId+"'.");
        else{
            if(pluginVersion!=null&&!pluginVersion.equals(plugin.version()))errors.add("Plugin version '"+pluginVersion+"' is not installed; available version is '"+plugin.version()+"'.");
            validateConfiguration(new PipelineNode("plugin",pluginId,plugin.version(),configuration),plugin,errors,requireCompleteConfiguration);
        }
        return new GraphValidation(errors.isEmpty(),List.copyOf(errors),List.of());
    }

    private static List<String> append(List<String> errors, String message) { errors.add(message); return List.copyOf(errors); }

    private static void validateConfiguration(PipelineNode node, PluginInfo plugin, List<String> errors, boolean requireComplete) {
        Map<String,Object> values=node.config()==null?Map.of():node.config();
        Map<String,ConfigOption> options=new HashMap<>();
        plugin.configuration().forEach(option->options.put(option.name(),option));
        if(requireComplete)for(ConfigOption option:plugin.configuration())if(option.required()&&!values.containsKey(option.name()))errors.add("Node '"+node.id()+"' is missing required configuration field '"+option.name()+"'.");
        for(var entry:values.entrySet()) {
            if(entry.getKey().equals("connectionRef")){
                if(!(entry.getValue() instanceof String reference)||reference.isBlank())errors.add("Node '"+node.id()+"' connectionRef must be a non-empty connection profile ID.");
                continue;
            }
            ConfigOption option=options.get(entry.getKey());
            if(option==null){errors.add("Node '"+node.id()+"' has unknown configuration field '"+entry.getKey()+"'.");continue;}
            if(entry.getValue()==null){errors.add("Node '"+node.id()+"' configuration field '"+entry.getKey()+"' must not be null.");continue;}
            Object value=entry.getValue();
            if(option.type().equals("string")&&!(value instanceof String))errors.add("Node '"+node.id()+"' configuration field '"+entry.getKey()+"' must be a string.");
            if(option.type().equals("integer")&&(!(value instanceof Number number)||number.doubleValue()!=Math.rint(number.doubleValue())))errors.add("Node '"+node.id()+"' configuration field '"+entry.getKey()+"' must be an integer.");
            if(option.type().equals("number")&&!(value instanceof Number))errors.add("Node '"+node.id()+"' configuration field '"+entry.getKey()+"' must be a number.");
            if(option.type().equals("boolean")&&!(value instanceof Boolean))errors.add("Node '"+node.id()+"' configuration field '"+entry.getKey()+"' must be a boolean.");
            if(value instanceof Number number){if(option.minimum()!=null&&number.doubleValue()<option.minimum())errors.add("Node '"+node.id()+"' field '"+entry.getKey()+"' must be at least "+option.minimum()+".");if(option.maximum()!=null&&number.doubleValue()>option.maximum())errors.add("Node '"+node.id()+"' field '"+entry.getKey()+"' must be at most "+option.maximum()+".");}
            if(option.sensitive() && (!(entry.getValue() instanceof String secretReference) || !secretReference.matches("secret://env/[A-Za-z_][A-Za-z0-9_]*")))
                errors.add("Node '"+node.id()+"' field '"+entry.getKey()+"' must reference an environment secret as 'secret://env/VARIABLE_NAME'; raw secrets are not stored in pipeline definitions.");
        }
    }

    private static void validateExecutionPolicy(PipelineNode node,List<String> errors){
        NodeExecutionPolicy policy=node.executionPolicy();if(policy==null)return;
        if(policy.maxRetries()<0||policy.maxRetries()>10)errors.add("Node '"+node.id()+"' maxRetries must be between 0 and 10.");
        if(policy.initialBackoffMillis()<0||policy.initialBackoffMillis()>60_000)errors.add("Node '"+node.id()+"' initialBackoffMillis must be between 0 and 60000.");
        if(policy.maxBackoffMillis()<policy.initialBackoffMillis()||policy.maxBackoffMillis()>300_000)errors.add("Node '"+node.id()+"' maxBackoffMillis must be at least initialBackoffMillis and at most 300000.");
        if(policy.timeoutMillis()<1||policy.timeoutMillis()>3_600_000)errors.add("Node '"+node.id()+"' timeoutMillis must be between 1 and 3600000.");
    }
}
