package com.core.docintel.pipeline.builder;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ProjectBuildManagerTest {
    @TempDir Path temp;

    @Test void nativeBundlePreservesRuntimeSidecarsBesideExecutable() throws Exception {
        Path target=Files.createDirectories(temp.resolve("native-project/target"));
        Files.writeString(target.resolve("Example.exe"),"native executable fixture");
        Files.writeString(target.resolve("awt.dll"),"native sidecar fixture");
        var entries=new java.util.HashMap<String,String>();
        try(var zip=new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(ProjectBuildManager.zipProject(target.getParent(),"Example")))){
            for(var entry=zip.getNextEntry();entry!=null;entry=zip.getNextEntry())entries.put(entry.getName(),new String(zip.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));
        }
        assertEquals("native executable fixture",entries.get("Example/target/Example.exe"));
        assertEquals("native sidecar fixture",entries.get("Example/target/awt.dll"));
    }

    @Test void requiresAbsoluteEmptyDirectoryAndRejectsFiles() throws Exception {
        assertThrows(IllegalArgumentException.class,()->PipelineProjectGenerator.validateDestination(Path.of("relative-project")));
        PipelineProjectGenerator.validateDestination(temp.resolve("new-project"));
        Path empty=Files.createDirectory(temp.resolve("empty"));
        PipelineProjectGenerator.validateDestination(empty);
        Files.writeString(empty.resolve("keep.txt"),"existing content");
        assertThrows(IllegalArgumentException.class,()->PipelineProjectGenerator.validateDestination(empty));
        assertThrows(IllegalArgumentException.class,()->PipelineProjectGenerator.validateDestination(empty.resolve("keep.txt")));
        assertEquals("existing content",Files.readString(empty.resolve("keep.txt")));
    }

    @Test void rejectsSymlinkDestinationAndAncestors() throws Exception {
        Path actual=Files.createDirectory(temp.resolve("actual"));
        Path link=temp.resolve("link");
        try { Files.createSymbolicLink(link,actual); }
        catch (java.io.IOException | UnsupportedOperationException e) {
            org.junit.jupiter.api.Assumptions.abort("Host does not permit symlink creation: "+e.getClass().getSimpleName());
        }
        assertThrows(IllegalArgumentException.class,()->PipelineProjectGenerator.validateDestination(link));
        assertThrows(IllegalArgumentException.class,()->PipelineProjectGenerator.validateDestination(link.resolve("child")));
        assertFalse(Files.exists(actual.resolve("child")));
    }

    @Test void projectBuildAuthorizationFollowsRoles() {
        var auth=new ControlPlaneAuthService(temp.resolve("auth.db").toString(),true,"admin","test-password-123");
        auth.initialize();
        for(String role:List.of("ADMIN","EDITOR","OPERATOR","VIEWER")) {
            var user=auth.createUser("user-"+role,"test-password-123",role);
            assertEquals(List.of("ADMIN","EDITOR").contains(role),auth.allowed(user,"/api/v1/projects/builds","POST"));
            assertTrue(auth.allowed(user,"/api/v1/projects/builds/job/bundle","GET"));
        }
        var manager=new ProjectBuildManager(null,null,null,new ControlPlaneAuthService(temp.resolve("disabled.db").toString(),false,"admin",""),"0.0.0.0");
        assertTrue(assertThrows(IllegalArgumentException.class,()->manager.start(new ProjectBuildRequest("pipeline",1,"Project",temp.resolve("project").toString(),"jar"))).getMessage().contains("loopback"));
    }
    @Test void convertsPublishedLinearGraphToOrderedLegacyLauncherRequest(){
        PipelineDefinition definition=new PipelineDefinition("Index documents",PipelineType.DOCUMENT_INGESTION,1,
                List.of(new PipelineNode("embed","embedding.ollama","1.0.0",Map.of("BATCH_SIZE",32,"connectionRef","profile-1")),
                        new PipelineNode("source","source.file","1.0.0",Map.of("PATH","./docs"))),
                List.of(new PipelineEdge("source","embed")));

        PipelineRequest request=ProjectBuildManager.chainRequest(definition,"IndexProject","jar");

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
                ()->ProjectBuildManager.chainRequest(definition,"BranchProject","jar"));
        assertTrue(error.getMessage().contains("single linear workflow"));
    }

    @Test void rejectsDisconnectedGraphsAndCycles(){
        PipelineDefinition disconnected=new PipelineDefinition("Disconnected",PipelineType.DOCUMENT_INGESTION,1,
                List.of(node("one"),node("two")),List.of());
        PipelineDefinition cycle=new PipelineDefinition("Cycle",PipelineType.DOCUMENT_INGESTION,1,
                List.of(node("one"),node("two")),List.of(new PipelineEdge("one","two"),new PipelineEdge("two","one")));

        assertTrue(assertThrows(IllegalArgumentException.class,()->ProjectBuildManager.chainRequest(disconnected,"Disconnected","jar"))
                .getMessage().contains("one connected linear workflow"));
        assertTrue(assertThrows(IllegalArgumentException.class,()->ProjectBuildManager.chainRequest(cycle,"Cycle","jar"))
                .getMessage().contains("cycle"));
    }

    private static PipelineNode node(String id){return new PipelineNode(id,"plugin."+id,"1.0.0",Map.of());}
}
