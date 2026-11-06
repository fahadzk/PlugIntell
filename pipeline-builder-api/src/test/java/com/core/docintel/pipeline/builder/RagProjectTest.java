package com.core.docintel.pipeline.builder;

import com.core.docintel.pipeline.builder.fixtures.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import java.util.concurrent.TimeUnit;
import javax.tools.ToolProvider;
import static org.junit.jupiter.api.Assertions.*;

class RagProjectTest {
    @TempDir Path temp;

    @Test void validatesEachLaneAndRejectsCrossLaneEdgesAndWrongUsage() throws Exception {
        PluginCatalog catalog=fixtures();GraphPipelineValidator validator=new GraphPipelineValidator();
        PipelineDefinition project=project();
        assertTrue(validator.validate(project,catalog.all(),true).valid());
        Map<PipelineType,PipelineWorkflow> workflows=new LinkedHashMap<>(project.workflows());
        workflows.put(PipelineType.QUERY_PROCESSING,new PipelineWorkflow(project.workflows().get(PipelineType.QUERY_PROCESSING).nodes(),List.of(new PipelineEdge("ingest","query"))));
        assertFalse(validator.validate(new PipelineDefinition("Bad",PipelineType.RAG_PROJECT,2,List.of(),List.of(),workflows),catalog.all()).valid());
        var wrong=new PipelineDefinition("Wrong",PipelineType.QUERY_PROCESSING,1,List.of(new PipelineNode("source","fixture-ingest","1.0.0",Map.of())),List.of());
        assertTrue(validator.validate(wrong,catalog.all()).errors().stream().anyMatch(error->error.contains("does not support")));
        workflows.put(PipelineType.QUERY_PROCESSING,new PipelineWorkflow(List.of(),List.of()));
        assertTrue(validator.validate(new PipelineDefinition("Partial",PipelineType.RAG_PROJECT,2,List.of(),List.of(),workflows),catalog.all()).valid());
    }

    @Test void persistsBothLanesAndKeepsPublishedVersionsImmutable() throws Exception {
        var store=new SqlitePipelineStore(temp.resolve("project.db").toString());store.initialize();
        var created=store.create(project());var version=store.publish(created.id(),created.revision());
        store.update(created.id(),2,new PipelineDefinition("Edited",PipelineType.DOCUMENT_INGESTION,1,project().workflow(PipelineType.DOCUMENT_INGESTION).nodes(),List.of()));
        var reopened=new SqlitePipelineStore(temp.resolve("project.db").toString());reopened.initialize();
        var saved=reopened.version(created.id(),version.version()).orElseThrow().definition();
        assertTrue(saved.hasWorkflow(PipelineType.DOCUMENT_INGESTION));assertTrue(saved.hasWorkflow(PipelineType.QUERY_PROCESSING));
        assertEquals("query-value",saved.workflow(PipelineType.QUERY_PROCESSING).nodes().getFirst().config().get("prefix"));
        assertFalse(reopened.get(created.id()).orElseThrow().definition().isProject());
    }

    @Test void generatedApplicationRunsBothModesWithIndependentConfigurationAndRelativeLibraries() throws Exception {
        Path project=compileProject(project());
        Files.writeString(project.resolve("sample.txt"),"Fixture input.");
        assertTrue(run(project,0,"--mode","ingestion","--input","sample.txt").contains("ingest-value:sample.txt"));
        assertTrue(run(project,0,"--mode","query","--query","sample question").contains("query-value:7:sample question"));
        PipelineDefinition timeoutDefinition=new PipelineDefinition("Timeout",PipelineType.QUERY_PROCESSING,1,List.of(new PipelineNode("query","fixture-query","1.0.0",Map.of("prefix","timeout","OLLAMA_TIMEOUT_SECONDS",60))),List.of());
        Path timeoutProject=compileProject(timeoutDefinition);
        assertTrue(run(timeoutProject,0,Map.of("OLLAMA_TIMEOUT_SECONDS","600"),"--mode","query","--query","sample").contains("timeout:600:sample"));
        assertTrue(run(timeoutProject,1,Map.of("OLLAMA_TIMEOUT_SECONDS","invalid"),"--mode","query","--query","sample").contains("OLLAMA_TIMEOUT_SECONDS must be a positive whole number"));
        assertTrue(run(project,1,"--mode","ingestion","--input","missing.pdf").contains("Input file does not exist or is not readable"));
        run(project,1,"--mode","unknown");run(project,1,"--mode","query","--input","sample.txt");run(project,1,"--mode","query");run(project,1,"--mode","query","--mode","ingestion");
    }

    @Test void generatedErrorsNameMissingSecretsAndRedactDefaultAndDebugOutput() throws Exception {
        var definition=new PipelineDefinition("Diagnostics",PipelineType.QUERY_PROCESSING,1,List.of(new PipelineNode("query","fixture-query","1.0.0",Map.of(
                "prefix","diagnostic","secretValue","secret://env/GENERATED_TEST_VALUE","failWithSecret",true))),List.of());
        Path project=compileProject(definition);
        String missing=run(project,1,"--mode","query","--query","sample");
        assertTrue(missing.contains("GENERATED_TEST_VALUE"));assertTrue(missing.contains("not set"));assertTrue(missing.contains("fixture-query"));
        String secret="fixture-private-value-"+java.util.UUID.randomUUID();
        String normal=run(project,1,Map.of("GENERATED_TEST_VALUE",secret),"--mode","query","--query","sample");
        assertTrue(normal.contains("[REDACTED]"));assertFalse(normal.contains(secret));
        String debug=run(project,1,Map.of("GENERATED_TEST_VALUE",secret,"TEST_PUBLIC_VALUE","visible-value","NEO4J_PASSWORD","private-password"),"--mode","query","--query","sample","--debug");
        assertTrue(debug.contains("[REDACTED]"));assertTrue(debug.contains("QueryFixture.configure"));assertFalse(debug.contains(secret));
        assertTrue(debug.contains("TEST_PUBLIC_VALUE=visible-value"));assertTrue(debug.contains("NEO4J_PASSWORD=[REDACTED]"));assertFalse(debug.contains("private-password"));
        assertTrue(run(project,1,"--mode","query").contains("Missing --query"));
        assertTrue(run(project,0,"--help").contains("--debug"));
    }

    private Path compileProject(PipelineDefinition definition) throws Exception {
        PluginCatalog catalog=fixtures();
        var generator=new PipelineProjectGenerator(new PipelineService(catalog),catalog,new MavenProjectBuilder("","",60));
        Path project=generator.createProject(definition,"Combined","jar",temp.resolve("generated-"+UUID.randomUUID()));
        Path classes=project.resolve("target/classes");Files.createDirectories(classes);
        String classpath;
        try(var libraries=Files.list(project.resolve("lib"))){classpath=String.join(java.io.File.pathSeparator,libraries.map(Path::toString).toList());}
        List<String> args=new ArrayList<>(List.of("--release","21","-proc:none","-classpath",classpath,"-d",classes.toString()));
        try(var sources=Files.list(project.resolve("src/main/java/generated/pipeline"))){sources.forEach(source->args.add(source.toString()));}
        assertEquals(0,ToolProvider.getSystemJavaCompiler().run(null,null,null,args.toArray(String[]::new)));
        Manifest manifest=new Manifest();manifest.getMainAttributes().putValue("Manifest-Version","1.0");manifest.getMainAttributes().putValue("Main-Class","generated.pipeline.GeneratedPipelineMain");
        String pom=Files.readString(project.resolve("pom.xml"));manifest.getMainAttributes().putValue("Class-Path",pom.split("<Class-Path>")[1].split("</Class-Path>")[0]);
        Path jar=project.resolve("target/combined-1.0.0.jar");
        try(var output=new JarOutputStream(Files.newOutputStream(jar),manifest);var paths=Files.walk(classes)){
            for(Path file:paths.filter(Files::isRegularFile).toList()){output.putNextEntry(new JarEntry(classes.relativize(file).toString().replace('\\','/')));Files.copy(file,output);output.closeEntry();}
        }
        return project;
    }

    @Test void executionSelectsOnlyRequestedPublishedLaneAndPersistsWorkflow() throws Exception {
        PluginCatalog catalog=fixtures();var validator=new GraphPipelineValidator();
        var store=new SqlitePipelineStore(temp.resolve("run.db").toString());store.initialize();
        var control=new PipelineControlService(store,validator,catalog);var record=control.create(project());control.publish(record.id(),record.revision());
        var executionStore=new SqliteExecutionStore(temp.resolve("run.db").toString());executionStore.initialize();
        try(var manager=new ExecutionManagerResource(new GraphExecutionManager(control,catalog,validator,executionStore,temp.toString()))){
            assertThrows(IllegalArgumentException.class,()->manager.value.start(new ExecutionRequest(record.id(),1,"question",null,null,null)));
            var accepted=manager.value.start(new ExecutionRequest(record.id(),1,"question",null,null,null),PipelineType.QUERY_PROCESSING);
            ExecutionSnapshot snapshot=null;
            for(int i=0;i<100;i++){snapshot=manager.value.get(accepted.executionId()).orElseThrow();if(snapshot.status()==ExecutionStatus.SUCCEEDED||snapshot.status()==ExecutionStatus.FAILED)break;Thread.sleep(50);}
            assertEquals(ExecutionStatus.SUCCEEDED,snapshot.status());assertEquals(PipelineType.QUERY_PROCESSING,snapshot.workflow());
            assertTrue(manager.value.events(accepted.executionId()).stream().noneMatch(event->"ingest".equals(event.nodeId())));
        }
    }
    private record ExecutionManagerResource(GraphExecutionManager value) implements AutoCloseable {public void close(){value.close();}}

    private String run(Path project,int expected,String... flags)throws Exception{return run(project,expected,Map.of(),flags);}
    private String run(Path project,int expected,Map<String,String> environment,String... flags)throws Exception{
        List<String> command=new ArrayList<>(List.of(Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString(),"-jar","target/combined-1.0.0.jar"));command.addAll(List.of(flags));
        Path outputFile=temp.resolve("process-output-"+UUID.randomUUID()+".log");
        ProcessBuilder builder=new ProcessBuilder(command).directory(project.toFile()).redirectErrorStream(true).redirectOutput(outputFile.toFile());
        builder.environment().remove("GENERATED_TEST_VALUE");builder.environment().putAll(environment);Process process=builder.start();
        assertTrue(process.waitFor(20,TimeUnit.SECONDS));String output=Files.readString(outputFile);assertEquals(expected,process.exitValue(),output);return output;
    }
    private PipelineDefinition project(){return new PipelineDefinition("Combined RAG",PipelineType.RAG_PROJECT,2,List.of(),List.of(),Map.of(
        PipelineType.DOCUMENT_INGESTION,new PipelineWorkflow(List.of(new PipelineNode("ingest","fixture-ingest","1.0.0",Map.of("prefix","ingest-value"))),List.of()),
        PipelineType.QUERY_PROCESSING,new PipelineWorkflow(List.of(new PipelineNode("query","fixture-query","1.0.0",Map.of("prefix","query-value","count",7))),List.of())));}
    private PluginCatalog fixtures()throws Exception{
        Path root=temp.resolve("plugins");Files.createDirectories(root);
        Path core=new PluginCatalog("").pipelineCoreJar(),copy=temp.resolve("pipeline-core/target/pipeline-core.jar");Files.createDirectories(copy.getParent());Files.copy(core,copy,StandardCopyOption.REPLACE_EXISTING);
        fixture(root,"fixture-ingest","Fixture ingestion",IngestFixture.class,"File",PipelineType.DOCUMENT_INGESTION);
        fixture(root,"fixture-query","Fixture query",QueryFixture.class,"SearchRequest",PipelineType.QUERY_PROCESSING);
        return new PluginCatalog(root.toString());
    }
    private void fixture(Path root,String id,String name,Class<?> type,String input,PipelineType workflow)throws Exception{
        Path module=root.resolve(id),metadata=module.resolve("src/main/resources/META-INF/PluginManifest.yaml");Files.createDirectories(metadata.getParent());
        String yaml="plugin:\n  id: "+id+"\n  name: "+name+"\n  version: 1.0.0\n  apiVersion: '1.0'\n  entrypoint: "+type.getName()+"\n  workflows: ["+workflow+"]\n  input: {type: "+input+"}\n  output: {type: String}\n  configuration:\n    properties:\n      prefix: {type: string}\n      count: {type: integer}\n      OLLAMA_TIMEOUT_SECONDS: {type: integer}\n      secretValue: {type: string, format: secret-reference, sensitive: true}\n      failWithSecret: {type: boolean}\n";
        Files.writeString(metadata,yaml);Files.writeString(module.resolve("pom.xml"),"<project><artifactId>"+id+"</artifactId></project>");Files.createDirectories(module.resolve("target"));
        try(var jar=new JarOutputStream(Files.newOutputStream(module.resolve("target/"+id+".jar")))){
            jar.putNextEntry(new JarEntry("META-INF/PluginManifest.yaml"));jar.write(yaml.getBytes(java.nio.charset.StandardCharsets.UTF_8));jar.closeEntry();
            String resource=type.getName().replace('.','/')+".class";jar.putNextEntry(new JarEntry(resource));try(var stream=type.getClassLoader().getResourceAsStream(resource)){assertNotNull(stream);stream.transferTo(jar);}jar.closeEntry();
        }
    }
}
