package com.core.docintel.pipeline.builder;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ConnectionProfileServiceTest {
    @TempDir Path temp;

    @Test void editsLiveDraftProfilesAndSnapshotsBothWorkflowsWhenPublishing() throws Exception {
        try(Context context=context()){
            var profile=context.service.create(request(null,"model-old","secret://env/PROFILE_SNAPSHOT_TEST_SECRET"));
            var draft=context.control.create(project(profile.id()));
            var first=context.control.publish(draft.id(),draft.revision());
            assertFrozen(first.definition(),"model-old","secret://env/PROFILE_SNAPSHOT_TEST_SECRET");
            assertEquals(profile.id(),context.control.get(draft.id()).orElseThrow().definition().workflow(PipelineType.QUERY_PROCESSING).nodes().getFirst().config().get("connectionRef"));
            var edited=context.service.update(profile.id(),request(profile.revision(),"model-new","secret://env/PROFILE_SNAPSHOT_NEW_SECRET"));
            assertThrows(ConnectionProfileConflictException.class,()->context.service.update(profile.id(),request(profile.revision(),"stale","secret://env/PROFILE_SNAPSHOT_TEST_SECRET")));
            var expanded=context.service.expand(context.control.get(draft.id()).orElseThrow().definition());
            assertFrozen(expanded,"model-new","secret://env/PROFILE_SNAPSHOT_NEW_SECRET");
            assertFrozen(context.control.version(draft.id(),1).orElseThrow().definition(),"model-old","secret://env/PROFILE_SNAPSHOT_TEST_SECRET");
            var latest=context.control.get(draft.id()).orElseThrow();var second=context.control.publish(draft.id(),latest.revision());
            assertFrozen(second.definition(),"model-new","secret://env/PROFILE_SNAPSHOT_NEW_SECRET");
            assertThrows(ConnectionProfileInUseException.class,()->context.service.delete(profile.id(),edited.revision()));
            latest=context.control.get(draft.id()).orElseThrow();context.control.update(draft.id(),latest.revision(),second.definition());
            context.service.delete(profile.id(),edited.revision());
            assertFrozen(context.control.executionDefinition(first.definition()),"model-old","secret://env/PROFILE_SNAPSHOT_TEST_SECRET");
            var reopened=new SqlitePipelineStore(temp.resolve("profile-test.db").toString());reopened.initialize();
            assertFrozen(reopened.version(draft.id(),1).orElseThrow().definition(),"model-old","secret://env/PROFILE_SNAPSHOT_TEST_SECRET");
            assertFrozen(reopened.version(draft.id(),2).orElseThrow().definition(),"model-new","secret://env/PROFILE_SNAPSHOT_NEW_SECRET");
        }
    }

    @Test void protectsLegacyVersionsBeforeEditingWithoutRewritingOriginalJson() throws Exception {
        try(Context context=context()){
            var profile=context.service.create(request(null,"original","secret://env/PROFILE_SNAPSHOT_TEST_SECRET"));
            var draft=context.control.create(project(profile.id()));context.pipelines.publish(draft.id(),draft.revision());
            String original=rawVersion(draft.id());assertTrue(original.contains("connectionRef"));
            var edited=context.service.update(profile.id(),request(profile.revision(),"updated","secret://env/PROFILE_SNAPSHOT_NEW_SECRET"));
            assertEquals(original,rawVersion(draft.id()));
            assertFrozen(context.pipelines.version(draft.id(),1).orElseThrow().definition(),"original","secret://env/PROFILE_SNAPSHOT_TEST_SECRET");
            // An older version first accessed through the control plane is also materialized before being returned.
            var other=context.control.create(project(profile.id()));context.pipelines.publish(other.id(),other.revision());
            var pinned=context.control.version(other.id(),1).orElseThrow();
            context.service.update(profile.id(),request(edited.revision(),"latest","secret://env/PROFILE_SNAPSHOT_TEST_SECRET"));
            assertFrozen(pinned.definition(),"updated","secret://env/PROFILE_SNAPSHOT_NEW_SECRET");
            assertFrozen(context.control.versions(draft.id()).getFirst().definition(),"original","secret://env/PROFILE_SNAPSHOT_TEST_SECRET");
            assertFrozen(context.control.version(other.id(),1).orElseThrow().definition(),"updated","secret://env/PROFILE_SNAPSHOT_NEW_SECRET");
        }
    }

    @Test void canDeleteProfileUsedOnlyByLegacyPublishedVersions() throws Exception {
        try(Context context=context()){
            var profile=context.service.create(request(null,"original","secret://env/PROFILE_SNAPSHOT_TEST_SECRET"));
            var draft=context.control.create(project(profile.id()));context.pipelines.publish(draft.id(),draft.revision());
            var current=context.control.get(draft.id()).orElseThrow();
            context.control.update(draft.id(),current.revision(),context.service.expand(current.definition()));
            context.service.delete(profile.id(),profile.revision());
            assertTrue(context.service.get(profile.id()).isEmpty());
            assertFrozen(context.control.version(draft.id(),1).orElseThrow().definition(),"original","secret://env/PROFILE_SNAPSHOT_TEST_SECRET");
        }
    }

    @Test void failedLegacySnapshotBatchRollsBackCompletely() throws Exception {
        try(Context context=context()){
            var profile=context.service.create(request(null,"original","secret://env/PROFILE_SNAPSHOT_TEST_SECRET"));
            var draft=context.control.create(project(profile.id()));context.pipelines.publish(draft.id(),draft.revision());
            var latest=context.control.get(draft.id()).orElseThrow();context.pipelines.publish(draft.id(),latest.revision());
            var count=new java.util.concurrent.atomic.AtomicInteger();
            assertThrows(IllegalStateException.class,()->context.pipelines.freezePublishedConnections(profile.id(),definition->{if(count.incrementAndGet()==2)throw new IllegalStateException("Snapshot failure");return context.service.expand(definition);}));
            assertTrue(context.pipelines.versions(draft.id()).stream().allMatch(version->version.definition().allNodes().stream().anyMatch(node->node.config().containsKey("connectionRef"))));
            assertEquals("original",context.service.get(profile.id()).orElseThrow().pluginConfigs().get("ollama-embedding").config().get("OLLAMA_EMBEDDING_MODEL"));
        }
    }

    private Context context(){
        var pipelines=new SqlitePipelineStore(temp.resolve("profile-test.db").toString());pipelines.initialize();
        var profiles=new SqliteConnectionProfileStore(temp.resolve("profile-test.db").toString());profiles.initialize();
        var catalog=new PluginCatalog("");var validator=new GraphPipelineValidator();var runner=new NodeTaskRunner();
        var service=new ConnectionProfileService(profiles,catalog,validator,new PluginConnectionTestService(catalog,validator,runner),pipelines);
        return new Context(pipelines,service,new PipelineControlService(pipelines,validator,catalog,service),runner);
    }
    private record Context(SqlitePipelineStore pipelines,ConnectionProfileService service,PipelineControlService control,NodeTaskRunner runner) implements AutoCloseable {public void close(){runner.close();}}
    private static ConnectionProfileRequest request(Long revision,String model,String secret){return new ConnectionProfileRequest("Shared settings","neo4j-retrieval",Map.of(),revision,Map.of(
            "neo4j-retrieval",new ConnectionPluginSettings("1.0.0",Map.of("NEO4J_PASSWORD",secret,"OLLAMA_EMBEDDING_MODEL",model)),
            "ollama-embedding",new ConnectionPluginSettings("1.0.0",Map.of("OLLAMA_EMBEDDING_MODEL",model))));}
    private static PipelineDefinition project(String profile){return new PipelineDefinition("Profile snapshots",PipelineType.RAG_PROJECT,2,List.of(),List.of(),Map.of(
            PipelineType.DOCUMENT_INGESTION,new PipelineWorkflow(List.of(new PipelineNode("source","extract-text","1.0.0",Map.of()),new PipelineNode("split","static-splitter","1.0.0",Map.of()),new PipelineNode("embed","ollama-embedding","1.0.0",Map.of("connectionRef",profile))),List.of(new PipelineEdge("source","split"),new PipelineEdge("split","embed"))),
            PipelineType.QUERY_PROCESSING,new PipelineWorkflow(List.of(new PipelineNode("retrieve","neo4j-retrieval","1.0.0",Map.of("connectionRef",profile,"RAG_SEARCH_TOP_K",7))),List.of())));}
    private static void assertFrozen(PipelineDefinition definition,String model,String secret){
        var embed=definition.workflow(PipelineType.DOCUMENT_INGESTION).nodes().get(2).config();var retrieve=definition.workflow(PipelineType.QUERY_PROCESSING).nodes().getFirst().config();
        assertFalse(embed.containsKey("connectionRef"));assertFalse(retrieve.containsKey("connectionRef"));
        assertEquals(model,embed.get("OLLAMA_EMBEDDING_MODEL"));assertEquals(model,retrieve.get("OLLAMA_EMBEDDING_MODEL"));
        assertEquals(secret,retrieve.get("NEO4J_PASSWORD"));assertEquals(7,((Number)retrieve.get("RAG_SEARCH_TOP_K")).intValue());
    }
    private String rawVersion(String id)throws Exception{
        try(var connection=java.sql.DriverManager.getConnection("jdbc:sqlite:"+temp.resolve("profile-test.db"));var query=connection.prepareStatement("SELECT definition_json FROM pipeline_versions WHERE pipeline_id=? AND version=1")){
            query.setString(1,id);try(var rows=query.executeQuery()){assertTrue(rows.next());return rows.getString(1);}
        }
    }

    @Test void oneProfileCanSupplySettingsToSeveralPluginNodes(){
        var pipelineStore=new SqlitePipelineStore(temp.resolve("multi-provider.db").toString());pipelineStore.initialize();
        var profileStore=new SqliteConnectionProfileStore(temp.resolve("multi-provider.db").toString());profileStore.initialize();
        var catalog=new PluginCatalog("");var validator=new GraphPipelineValidator();var runner=new NodeTaskRunner();
        try{
            var service=new ConnectionProfileService(profileStore,catalog,validator,new PluginConnectionTestService(catalog,validator,runner),pipelineStore);
            var configurations=Map.of(
                    "neo4j-retrieval",new ConnectionPluginSettings("1.0.0",Map.of("NEO4J_PASSWORD","secret://env/NEO4J_PASSWORD","OLLAMA_EMBEDDING_MODEL","nomic-embed-text")),
                    "ollama-embedding",new ConnectionPluginSettings("1.0.0",Map.of("OLLAMA_EMBEDDING_MODEL","nomic-embed-text","OLLAMA_BASE_URL","http://localhost:11434")));
            var profile=service.create(new ConnectionProfileRequest("Home RAG","neo4j-retrieval",Map.of(),null,configurations));
            var definition=new PipelineDefinition("Shared provider settings",PipelineType.QUERY_PROCESSING,1,List.of(
                    new PipelineNode("retrieve","neo4j-retrieval","1.0.0",Map.of("connectionRef",profile.id())),
                    new PipelineNode("embed","ollama-embedding","1.0.0",Map.of("connectionRef",profile.id()))),List.of());
            var expanded=service.expand(definition);
            assertEquals("secret://env/NEO4J_PASSWORD",expanded.nodes().getFirst().config().get("NEO4J_PASSWORD"));
            assertEquals("http://localhost:11434",expanded.nodes().get(1).config().get("OLLAMA_BASE_URL"));
            assertEquals("nomic-embed-text",expanded.nodes().get(1).config().get("OLLAMA_EMBEDDING_MODEL"));
        }finally{runner.close();}
    }
}
