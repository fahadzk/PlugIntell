package com.core.docintel.pipeline.builder;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class GraphPipelineValidatorTest {
    private final GraphPipelineValidator validator = new GraphPipelineValidator();
    private final List<PluginInfo> plugins = List.of(
            plugin("source", "File", "Text"), plugin("clean", "Text", "Text"), plugin("store", "Text", "Stored"));

    @Test void validatesTypedDagAndReturnsDeterministicTopologicalOrder() {
        var result = validator.validate(definition(
                List.of(node("b", "clean"), node("a", "source"), node("c", "store")),
                List.of(new PipelineEdge("a", "b"), new PipelineEdge("b", "c"))), plugins);
        assertTrue(result.valid(), result.errors().toString());
        assertEquals(List.of("a", "b", "c"), result.executionOrder());
    }

    @Test void rejectsCyclesAndTypeMismatch() {
        var cycle = validator.validate(definition(List.of(node("a", "source"), node("b", "clean")),
                List.of(new PipelineEdge("a", "b"), new PipelineEdge("b", "a"))), plugins);
        assertFalse(cycle.valid());
        assertTrue(cycle.errors().stream().anyMatch(e -> e.contains("cycle")));
        assertTrue(cycle.errors().stream().anyMatch(e -> e.contains("expects File")));
    }

    @Test void allowsFanInOnlyForPluginsDeclaringTheCapability() {
        var nodes=List.of(node("root","source"),node("left","clean"),node("right","clean"),node("join","join"));
        var edges=List.of(new PipelineEdge("root","left"),new PipelineEdge("root","right"),new PipelineEdge("left","join"),new PipelineEdge("right","join"));
        PluginInfo merger=new PluginInfo("join","join","1.0.0","1.0","join.Plugin","join","Text","Text","",List.of("fan-in"),List.of());
        var valid=validator.validate(definition(nodes,edges),List.of(plugins.get(0),plugins.get(1),merger));
        assertTrue(valid.valid(),valid.errors().toString());
        assertEquals(List.of("root","left","right","join"),valid.executionOrder());
        PluginInfo regular=plugin("join","Text","Text");
        var invalid=validator.validate(definition(nodes,edges),List.of(plugins.get(0),plugins.get(1),regular));
        assertFalse(invalid.valid());
        assertTrue(invalid.errors().stream().anyMatch(error->error.contains("does not declare the fan-in capability")));
    }

    @Test void rejectsUnknownPluginDuplicateNodeAndVersionDrift() {
        var result = validator.validate(definition(List.of(node("a", "missing"), node("a", "source"),
                new PipelineNode("b", "clean", "9.0", Map.of())), List.of()), plugins);
        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("Unknown plugin")));
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("Duplicate node")));
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("available version")));
    }

    @Test void rejectsUnknownConfigAndPlaintextSecrets() {
        PluginInfo withConfig = new PluginInfo("source", "source", "1.0.0", "1.0", "source.Plugin", "source", "File", "Text", "", List.of(),
                List.of(new ConfigOption("API_KEY", "", false, true, "Secret reference"), new ConfigOption("recursive", "true", false, false, "Walk child paths")));
        var raw = validator.validate(definition(List.of(new PipelineNode("a", "source", "1.0.0", Map.of("API_KEY", "plaintext-secret", "unknown", "x"))), List.of()), List.of(withConfig));
        assertFalse(raw.valid());
        assertTrue(raw.errors().stream().anyMatch(e -> e.contains("environment secret")));
        assertTrue(raw.errors().stream().anyMatch(e -> e.contains("unknown configuration field")));
        var ref = validator.validate(definition(List.of(new PipelineNode("a", "source", "1.0.0", Map.of("API_KEY", "secret://env/NEO4J_PASSWORD"))), List.of()), List.of(withConfig));
        assertTrue(ref.valid(), ref.errors().toString());
    }

    @Test void enforcesPipelineKindAgainstRootInputContract(){
        var wrongRoot=validator.validate(new PipelineDefinition("query",PipelineType.QUERY_PROCESSING,1,
                List.of(node("root","source")),List.of()),plugins);
        assertFalse(wrongRoot.valid());
        assertTrue(wrongRoot.errors().stream().anyMatch(error->error.contains("must accept SearchRequest")));
        var queryPlugins=List.of(plugin("query","SearchRequest","Text"));
        var query=validator.validate(new PipelineDefinition("query",PipelineType.QUERY_PROCESSING,1,
                List.of(node("root","query")),List.of()),queryPlugins);
        assertTrue(query.valid(),query.errors().toString());
    }

    @Test void validatesPluginConfigurationIndependentlyOfPipelineRootType(){
        PluginInfo chunker=new PluginInfo("chunker","Chunker","1.0.0","1.0","chunker.Plugin","chunker","TextDocument","ChunkCollection","",List.of(),
                List.of(new ConfigOption("size","integer","80",true,false,"Chunk size",1,1000)));
        var valid=validator.validatePluginConfiguration("chunker","1.0.0",Map.of("size",128),List.of(chunker),true);
        assertTrue(valid.valid(),valid.errors().toString());
        var invalid=validator.validatePluginConfiguration("chunker","1.0.0",Map.of("other",128),List.of(chunker),true);
        assertFalse(invalid.valid());
    }

    @Test void boundsRetriesBackoffAndTimeouts(){
        var bounded=validator.validate(definition(List.of(new PipelineNode("a","source","1.0.0",Map.of(),new NodeExecutionPolicy(2,50,500,10_000))),List.of()),plugins);
        assertTrue(bounded.valid(),bounded.errors().toString());
        var invalid=validator.validate(definition(List.of(new PipelineNode("a","source","1.0.0",Map.of(),new NodeExecutionPolicy(11,50,20,0))),List.of()),plugins);
        assertFalse(invalid.valid());
        assertTrue(invalid.errors().stream().anyMatch(error->error.contains("maxRetries")));
        assertTrue(invalid.errors().stream().anyMatch(error->error.contains("maxBackoffMillis")));
        assertTrue(invalid.errors().stream().anyMatch(error->error.contains("timeoutMillis")));
    }

    private static PipelineDefinition definition(List<PipelineNode> nodes, List<PipelineEdge> edges) {
        return new PipelineDefinition("test", PipelineType.DOCUMENT_INGESTION, 1, nodes, edges);
    }
    private static PipelineNode node(String id, String plugin) { return new PipelineNode(id, plugin, "1.0.0", Map.of()); }
    private static PluginInfo plugin(String id, String input, String output) {
        return new PluginInfo(id, id, "1.0.0", "1.0", id + ".Plugin", id, input, output, "", List.of(), List.of());
    }
}
