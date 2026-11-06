package com.core.docintel.pipeline.builder;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ConnectionProfileServiceTest {
    @TempDir Path temp;

    @Test void expandsProfilesForValidationAndProtectsReferencedSettings(){
        var pipelineStore=new SqlitePipelineStore(temp.resolve("studio.db").toString());pipelineStore.initialize();
        var profileStore=new SqliteConnectionProfileStore(temp.resolve("studio.db").toString());profileStore.initialize();
        var catalog=new PluginCatalog("");var validator=new GraphPipelineValidator();var runner=new NodeTaskRunner();
        try{
            var service=new ConnectionProfileService(profileStore,catalog,validator,new PluginConnectionTestService(catalog,validator,runner),pipelineStore);
            var provider=catalog.all().stream().filter(plugin->plugin.id().equals("neo4j-retrieval")).findFirst().orElseThrow();
            var profile=service.create(new ConnectionProfileRequest("Primary Neo4j",provider.id(),Map.of(
                    "NEO4J_PASSWORD","secret://env/NEO4J_PASSWORD",
                    "OLLAMA_EMBEDDING_MODEL","nomic-embed-text"),null));
            var control=new PipelineControlService(pipelineStore,validator,catalog,service);
            var definition=new PipelineDefinition("Profile-backed query",PipelineType.QUERY_PROCESSING,1,
                    List.of(new PipelineNode("retrieve",provider.id(),provider.version(),Map.of("connectionRef",profile.id()))),List.of());
            assertTrue(control.validate(definition,true).valid());
            control.create(definition);
            assertThrows(ConnectionProfileInUseException.class,()->service.update(profile.id(),new ConnectionProfileRequest("Changed",null,profile.config(),profile.revision())));
            assertThrows(ConnectionProfileInUseException.class,()->service.delete(profile.id(),profile.revision()));
        }finally{runner.close();}
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
