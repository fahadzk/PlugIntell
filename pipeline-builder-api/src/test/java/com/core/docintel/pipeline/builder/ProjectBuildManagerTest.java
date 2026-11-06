package com.core.docintel.pipeline.builder;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ProjectBuildManagerTest {
    @Test void convertsPublishedLinearGraphToOrderedLegacyLauncherRequest(){
        PipelineDefinition definition=new PipelineDefinition("Index documents",PipelineType.DOCUMENT_INGESTION,1,
                List.of(new PipelineNode("embed","embedding.ollama","1.0.0",Map.of("BATCH_SIZE",32,"connectionRef","profile-1")),
                        new PipelineNode("source","source.file","1.0.0",Map.of("PATH","./docs"))),
                List.of(new PipelineEdge("source","embed")));

        PipelineRequest request=ProjectBuildManager.legacyRequest(definition,"IndexProject","jar");

        assertEquals(List.of("source.file","embedding.ollama"),request.pluginIds());
        assertEquals("./docs",request.configuration().get("PATH"));
        assertEquals("32",request.configuration().get("BATCH_SIZE"));
        assertFalse(request.configuration().containsKey("connectionRef"));
        assertEquals("jar",request.outputFormat());
    }

    @Test void rejectsBranchingGraphsThatCannotBecomeAPluginChain(){
        PipelineDefinition definition=new PipelineDefinition("Branch",PipelineType.DOCUMENT_INGESTION,1,
                List.of(node("root"),node("left"),node("right")),
                List.of(new PipelineEdge("root","left"),new PipelineEdge("root","right")));

        IllegalArgumentException error=assertThrows(IllegalArgumentException.class,
                ()->ProjectBuildManager.legacyRequest(definition,"BranchProject","jar"));
        assertTrue(error.getMessage().contains("single linear workflow"));
    }

    @Test void rejectsDisconnectedGraphsAndCycles(){
        PipelineDefinition disconnected=new PipelineDefinition("Disconnected",PipelineType.DOCUMENT_INGESTION,1,
                List.of(node("one"),node("two")),List.of());
        PipelineDefinition cycle=new PipelineDefinition("Cycle",PipelineType.DOCUMENT_INGESTION,1,
                List.of(node("one"),node("two")),List.of(new PipelineEdge("one","two"),new PipelineEdge("two","one")));

        assertTrue(assertThrows(IllegalArgumentException.class,()->ProjectBuildManager.legacyRequest(disconnected,"Disconnected","jar"))
                .getMessage().contains("one connected linear workflow"));
        assertTrue(assertThrows(IllegalArgumentException.class,()->ProjectBuildManager.legacyRequest(cycle,"Cycle","jar"))
                .getMessage().contains("cycle"));
    }

    private static PipelineNode node(String id){return new PipelineNode(id,"plugin."+id,"1.0.0",Map.of());}
}
