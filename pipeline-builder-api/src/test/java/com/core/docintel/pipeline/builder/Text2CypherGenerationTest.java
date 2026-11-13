package com.core.docintel.pipeline.builder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.jar.JarFile;
import javax.tools.ToolProvider;
import static org.junit.jupiter.api.Assertions.*;

class Text2CypherGenerationTest {
    @TempDir Path temporary;
    @Test void exportedQueryIncludesOnlySelectedPluginsProviderPromptsAndCompiles()throws Exception{
        Path repository=Files.isDirectory(Path.of("plugins"))?Path.of("."):Path.of("..");var catalog=new PluginCatalog(repository.resolve("plugins").toString());
        var definition=new PipelineDefinition("External graph",PipelineType.QUERY_PROCESSING,1,List.of(
            new PipelineNode("retrieve","neo4j-text2cypher-retrieval","1.0.0",Map.of("LLM_PROVIDER","OLLAMA","OLLAMA_CHAT_MODEL","fixture","NEO4J_PASSWORD","secret://env/NEO4J_PASSWORD","NEO4J_DATABASE","pakistan-railways")),
            new PipelineNode("display","universal-response","1.0.0",Map.of("UR_UI_ENABLED",false))),List.of(new PipelineEdge("retrieve","display")));
        var validation=new GraphPipelineValidator().validate(definition,catalog.all());assertTrue(validation.valid(),validation.errors().toString());
        var generator=new PipelineProjectGenerator(new PipelineService(catalog),catalog,new MavenProjectBuilder("","",60),new TemplateCatalog(temporary.resolve("templates").toString()));
        Path project=generator.createProject(definition,"ExternalGraph","jar",temporary.resolve("project"));
        var query=Files.readString(project.resolve("src/main/java/generated/pipeline/QueryPipeline.java"));assertTrue(query.contains("CliSupport.text2CypherSettings("));assertTrue(query.contains("Neo4JText2CypherRetrievalPlugin"));
        assertTrue(Files.isRegularFile(project.resolve("lib/neo4j-text2cypher-retrieval-plugin.jar")));assertFalse(Files.exists(project.resolve("lib/ollama-neo4j-graph-extraction-plugin.jar")));
        try(var jar=new JarFile(project.resolve("lib/neo4j-text2cypher-retrieval-plugin.jar").toFile())){
            assertNotNull(jar.getEntry("META-INF/PluginManifest.yaml"));assertNotNull(jar.getEntry("com/core/docintel/llm/ExtractionModelClient.class"));assertNull(jar.getEntry("com/core/docintel/pipeline/api/RetrievalResult.class"));
        }
        Path classes=project.resolve("target/classes");Files.createDirectories(classes);String classpath;
        try(var jars=Files.list(project.resolve("lib"))){classpath=String.join(java.io.File.pathSeparator,jars.map(Path::toString).toList());}
        List<String> arguments=new ArrayList<>(List.of("--release","21","-proc:none","-classpath",classpath,"-d",classes.toString()));try(var sources=Files.list(project.resolve("src/main/java/generated/pipeline"))){sources.forEach(f->arguments.add(f.toString()));}
        assertEquals(0,ToolProvider.getSystemJavaCompiler().run(null,null,null,arguments.toArray(String[]::new)));
    }
}
