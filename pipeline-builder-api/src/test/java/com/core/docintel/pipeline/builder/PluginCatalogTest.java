package com.core.docintel.pipeline.builder;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class PluginCatalogTest {
    @Test void extractionRequiresOnlyTheChosenProviderModelAndSecret(){
        Path root=Files.isDirectory(Path.of("plugins"))?Path.of("plugins"):Path.of("../plugins");var catalog=new PluginCatalog(root.toString());var validator=new GraphPipelineValidator();
        for(String provider:java.util.List.of("GEMINI","OPENAI")){
            var config=new java.util.HashMap<String,Object>(Map.of("LLM_PROVIDER",provider,"NEO4J_PASSWORD","secret://env/NEO4J_PASSWORD"));
            var missing=validator.validatePluginConfiguration("ollama-neo4j-graph-extraction","1.0.0",config,catalog.all(),true);assertFalse(missing.valid());assertTrue(missing.errors().stream().anyMatch(e->e.contains(provider+"_MODEL")));
            config.put(provider+"_MODEL","custom-future-model");config.put(provider+"_API_KEY","secret://env/"+provider+"_API_KEY");assertTrue(validator.validatePluginConfiguration("ollama-neo4j-graph-extraction","1.0.0",config,catalog.all(),true).valid());
            config.put(provider+"_API_KEY","raw-secret");assertFalse(validator.validatePluginConfiguration("ollama-neo4j-graph-extraction","1.0.0",config,catalog.all(),true).valid());
        }
    }
    @Test void shippedGraphProjectsValidateWithInstalledPluginPortsAndCompleteConfiguration() throws Exception {
        Path repository=Files.isDirectory(Path.of("plugins"))?Path.of("."):Path.of("..");
        var catalog=new PluginCatalog(repository.resolve("plugins").toString());
        var json=new com.fasterxml.jackson.databind.ObjectMapper();
        for(String domain:java.util.List.of("engineering","heritage")){
            var definition=json.readValue(repository.resolve("examples/knowledge-graph/"+domain+"-project.json").toFile(),PipelineDefinition.class);
            var validation=new GraphPipelineValidator().validate(definition,catalog.all(),true);
            assertTrue(validation.valid(),()->domain+": "+validation.errors());
        }
    }
    @Test void graphPluginsPublishPortsEnumsConditionsAndSecretReferences(){
        Path root=Files.isDirectory(Path.of("plugins"))?Path.of("plugins"):Path.of("../plugins");var catalog=new PluginCatalog(root.toString());
        var extraction=catalog.descriptor("ollama-neo4j-graph-extraction").orElseThrow();var retrieval=catalog.descriptor("ollama-neo4j-graph-retrieval").orElseThrow();
        assertEquals("ChunkCollection",extraction.inputs().getFirst().dataType());assertEquals("KnowledgeGraphResult",extraction.outputs().getFirst().dataType());assertEquals("RetrievalResult",retrieval.outputs().getFirst().dataType());
        assertEquals(java.util.List.of(PipelineType.DOCUMENT_INGESTION),extraction.workflows());assertEquals(java.util.List.of(PipelineType.QUERY_PROCESSING),retrieval.workflows());
        var fields=(Map<?,?>)extraction.configurationSchema().get("properties");assertEquals(java.util.List.of("OPEN","STRICT"),((Map<?,?>)fields.get("KG_SCHEMA_MODE")).get("enum"));assertEquals(5,((java.util.List<?>)extraction.configurationSchema().get("allOf")).size());
        assertNull(((Map<?,?>)fields.get("NEO4J_PASSWORD")).get("default"));
        assertEquals("json",((Map<?,?>)fields.get("KG_PROPERTY_SCHEMAS")).get("format"));assertEquals("multiline",((Map<?,?>)fields.get("KG_PROMPT_TEMPLATE")).get("format"));
        var config=new java.util.HashMap<String,Object>(Map.of("OLLAMA_CHAT_MODEL","fixture","NEO4J_PASSWORD","secret://env/NEO4J_PASSWORD","KG_SCHEMA_MODE","STRICT"));
        var validator=new GraphPipelineValidator();var invalid=validator.validatePluginConfiguration(extraction.id(),extraction.version(),config,catalog.all(),true);assertFalse(invalid.valid());assertTrue(invalid.errors().stream().anyMatch(e->e.contains("KG_ENTITY_TYPES")));
        config.put("KG_ENTITY_TYPES","Component");config.put("KG_RELATIONSHIP_TYPES","POWERS");assertTrue(validator.validatePluginConfiguration(extraction.id(),extraction.version(),config,catalog.all(),true).valid());
        config.put("KG_SCHEMA_MODE","invented-mode");assertFalse(validator.validatePluginConfiguration(extraction.id(),extraction.version(),config,catalog.all(),true).valid());
        config.put("KG_SCHEMA_MODE","OPEN");config.put("KG_PROPERTY_SCHEMAS","{broken}");assertFalse(validator.validatePluginConfiguration(extraction.id(),extraction.version(),config,catalog.all(),true).valid());
    }
    @Test void publishesTypedSchemasFromPluginManifestsWithoutReturningSecrets(){
        Path root=Path.of("plugins");if(!Files.isDirectory(root))root=Path.of("..","plugins");
        PluginCatalog catalog=new PluginCatalog(root.toAbsolutePath().normalize().toString());
        PluginDescriptor embedding=catalog.descriptor("ollama-embedding").orElseThrow();
        assertEquals("EMBEDDING_MODEL",embedding.category());
        @SuppressWarnings("unchecked") Map<String,Object> properties=(Map<String,Object>)embedding.configurationSchema().get("properties");
        assertEquals("integer",((Map<?,?>)properties.get("OLLAMA_TIMEOUT_SECONDS")).get("type"));
        assertTrue(((java.util.List<?>)embedding.configurationSchema().get("required")).contains("OLLAMA_EMBEDDING_MODEL"));
        PluginDescriptor writer=catalog.descriptor("neo4j-writer").orElseThrow();
        @SuppressWarnings("unchecked") Map<String,Object> writerProperties=(Map<String,Object>)writer.configurationSchema().get("properties");
        Map<?,?> password=(Map<?,?>)writerProperties.get("NEO4J_PASSWORD");
        assertEquals(true,password.get("writeOnly"));
        assertNull(password.get("default"));
        PluginDescriptor merge=catalog.descriptor("document-merge").orElseThrow();
        assertEquals("MANY",merge.inputs().getFirst().cardinality());
        assertTrue(merge.capabilities().contains("fan-in"));
        assertTrue(catalog.descriptor("static-splitter").orElseThrow().capabilities().contains("parallel-safe"));
    }

    @Test void reloadFindsNewLocalManifestWithoutRestartingCatalog() throws Exception {
        Path root=java.nio.file.Files.createTempDirectory("plugin-catalog-reload");
        PluginCatalog catalog=new PluginCatalog(root.toString());
        assertTrue(catalog.all().isEmpty());
        Path module=root.resolve("added-plugin");
        Path manifest=module.resolve("src/main/resources/META-INF/PluginManifest.yaml");
        java.nio.file.Files.createDirectories(manifest.getParent());
        java.nio.file.Files.writeString(manifest,"""
                plugin:
                  id: added-plugin
                  name: Added Plugin
                  version: 1.0.0
                  apiVersion: "1.0"
                  entrypoint: com.example.AddedPlugin
                  input:
                    type: Text
                  output:
                    type: Text
                """);
        java.nio.file.Files.writeString(module.resolve("pom.xml"),"<project><artifactId>added-plugin</artifactId></project>");
        assertTrue(catalog.reload().stream().anyMatch(plugin->plugin.id().equals("added-plugin")));
    }
}
