package com.core.docintel.pipeline.builder;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class PluginCatalogTest {
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
