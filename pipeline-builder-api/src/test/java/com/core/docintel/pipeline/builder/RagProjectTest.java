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

    @Test void interactiveCliPromptsForCitationsAndMissingSecretWithoutLeakingIt()throws Exception{
        var definition=new PipelineDefinition("Interactive",PipelineType.QUERY_PROCESSING,1,List.of(new PipelineNode("query","fixture-query","1.0.0",Map.of("prefix","prompt","secretValue","secret://env/GENERATED_TEST_VALUE","failWithSecret",true))),List.of());
        Path project=compileProject(definition),log=temp.resolve("interactive.log");
        var builder=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-jar","target/combined-1.0.0.jar","--mode","query","--query","sample").directory(project.toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
        builder.environment().remove("GENERATED_TEST_VALUE");builder.environment().remove("RAG_SEARCH_INCLUDE_CITATIONS");
        Process process=builder.start();try{
            try(var stream=process.getOutputStream()){stream.write("n\nunique-prompted-secret-123\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));}
            assertTrue(process.waitFor(30,TimeUnit.SECONDS));assertEquals(1,process.exitValue());String output=Files.readString(log);
            assertTrue(output.contains("Display source citations?"));assertTrue(output.contains("GENERATED_TEST_VALUE is not set"));assertTrue(output.contains("citations: off"));assertFalse(output.contains("unique-prompted-secret-123"));assertTrue(output.contains("[REDACTED]"));
        }finally{process.destroyForcibly();}
    }

    @Test void incompleteGraphHasCompactOutputAndNonzeroExitCode()throws Exception{
        var definition=new PipelineDefinition("Graph",PipelineType.DOCUMENT_INGESTION,1,List.of(new PipelineNode("graph","fixture-graph","1.0.0",Map.of())),List.of());
        Path project=compileProject(definition);Files.writeString(project.resolve("sample.txt"),"sample");
        String output=run(project,2,"--mode","ingestion","--input","sample.txt","--citations","no");
        assertTrue(output.contains("INCOMPLETE"));assertTrue(output.contains("Entities: 0 | Relationships: 0"));assertFalse(output.contains("ExtractedGraph["));assertFalse(output.contains("PRIVATE_DOCUMENT_BODY"));assertFalse(output.contains("Pipeline completed successfully"));
    }

    @Test void nativeWebGenerationIncludesHostAssetsAndDirectExecutableLaunchers() throws Exception {
        int port;try(var socket=new java.net.ServerSocket(0)){port=socket.getLocalPort();}
        var base=project();var workflows=new EnumMap<PipelineType,PipelineWorkflow>(PipelineType.class);workflows.putAll(base.workflows());
        workflows.put(PipelineType.QUERY_PROCESSING,new PipelineWorkflow(List.of(new PipelineNode("answer","fixture-answer","1.0.0",Map.of())),List.of()));
        var capabilities=Map.<String,Object>of("host",Map.of("type","micronaut-web","port",port,"openBrowser",false),
            "adapters",List.of(Map.of("id","ui","type","ui","version","1","config",Map.of())),
            "subscriptions",List.of(Map.of("adapterId","ui","workflow","DOCUMENT_INGESTION"),Map.of("adapterId","ui","workflow","QUERY_PROCESSING")),
            "template",Map.of("id","water","version","1","settings",Map.of()));
        var definition=new PipelineDefinition(base.name(),base.type(),base.schemaVersion(),base.nodes(),base.edges(),workflows,capabilities);
        var catalog=fixtures();var generator=new PipelineProjectGenerator(new PipelineService(catalog),catalog,new MavenProjectBuilder("","",60),new TemplateCatalog(temp.resolve("templates").toString()));
        String export=System.getProperty("pipeline.native-smoke-dir");
        Path destination=export==null?temp.resolve("native web project"):Path.of(export);
        Path project=generator.createProject(definition,"NativeWeb","native-image",destination);
        String pom=Files.readString(project.resolve("pom.xml")),batch=Files.readString(project.resolve("run.bat"));
        var xml=javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(project.resolve("pom.xml").toFile());
        assertNotNull(xml.getDocumentElement());assertTrue(pom.contains("<id>native</id>"));assertTrue(pom.contains("<mainClass>generated.pipeline.GeneratedPipelineMain</mainClass>"));
        assertTrue(pom.contains("${project.basedir}/lib/pipeline-web.jar</param>"));assertTrue(pom.contains("--initialize-at-run-time=io.netty,org.slf4j"));
        assertTrue(batch.contains("\"target\\NativeWeb.exe\" %*"));assertFalse(batch.contains(" -jar "));assertTrue(batch.contains("run.bat serve"));
        assertTrue(Files.readString(project.resolve("run.sh")).contains("exec ./target/NativeWeb \"$@\""));
        assertTrue(Files.readString(project.resolve("README.md")).contains("Java and lib/ are not needed to run it"));
        assertTrue(Files.readString(project.resolve("src/main/resources/public/monitor.html")).contains("host-answer"));
        try(var jar=new java.util.zip.ZipFile(project.resolve("lib/pipeline-web.jar").toFile())){
            assertNotNull(jar.getEntry("META-INF/native-image/com.core.docintel/pipeline-web/resource-config.json"));
            assertNull(jar.getEntry("io/micronaut/graal/reflect/GraalTypeElementVisitor.class"),"The build-time processor must not be shipped in the host");
        }
        Path cli=generator.createProject(base,"NativeCli","native-image",temp.resolve("native cli"));
        String cliPom=Files.readString(cli.resolve("pom.xml"));
        assertFalse(cliPom.contains("pipeline-web"));assertFalse(cliPom.contains("--initialize-at-run-time=io.netty"));
        assertFalse(Files.exists(cli.resolve("src/main/resources/public")));
        assertFalse(Files.readString(cli.resolve("run.bat")).contains("run.bat serve"));
        if(export!=null)Files.writeString(project.resolve("native-smoke-port.txt"),Integer.toString(port));
    }

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

    @Test void minimalGenerationUsesExplicitAssemblyAndExcludesOptionalDependencies() throws Exception {
        Path project=compileProject(project());
        String pom=Files.readString(project.resolve("pom.xml"));
        assertFalse(pom.contains("pipeline-web"));assertFalse(pom.contains("kafka"));assertFalse(pom.contains("micronaut"));
        assertFalse(run(project,0,"--help").contains("serve"));
        assertFalse(Files.readString(project.resolve("run.bat")).contains("run.bat serve"));
        assertFalse(Files.readString(project.resolve("README.md")).contains("run.bat serve"));
        assertTrue(run(project,1,"serve").contains("Invalid, missing, or duplicate flag"));
        assertTrue(Files.isRegularFile(project.resolve("src/main/java/generated/pipeline/PipelineAssembly.java")));
        try(var paths=Files.list(project.resolve("src/main/java/generated/pipeline"))){for(Path source:paths.toList()){String text=Files.readString(source);assertFalse(text.contains("Class.forName"));assertFalse(text.contains("ServiceLoader"));assertFalse(text.contains("getDeclaredConstructor"));}}
    }

    @Test void generatedWebApplicationServesBundledMonitorAndMultipleRuns() throws Exception {verifyGeneratedWeb("water");}
    @Test void generatedRichExternalThemesServePinnedAssetsSettingsAndQueryResults() throws Exception {for(String theme:List.of("aurora","midnight"))verifyGeneratedWeb(theme);}
    private void verifyGeneratedWeb(String theme) throws Exception {
        int port;try(var socket=new java.net.ServerSocket(0)){port=socket.getLocalPort();}
        String templateId=theme.equals("water")?theme:new TemplateCatalog(temp.resolve("templates").toString()).list().stream().map(item->item.get("id").toString()).filter(id->id.startsWith(theme+"-")).findFirst().orElseThrow();
        var original=project();
        var workflows=new java.util.EnumMap<PipelineType,PipelineWorkflow>(PipelineType.class);workflows.putAll(original.workflows());
        workflows.put(PipelineType.QUERY_PROCESSING,new PipelineWorkflow(List.of(new PipelineNode("answer","fixture-answer","1.0.0",Map.of())),List.of()));
        var base=new PipelineDefinition(original.name(),original.type(),original.schemaVersion(),original.nodes(),original.edges(),workflows);
        Map<String,Object> capabilities=Map.of("adapters",List.of(Map.of("id","ui","type","ui","version","1","config",Map.of())),
            "subscriptions",List.of(Map.of("adapterId","ui","workflow","DOCUMENT_INGESTION"),Map.of("adapterId","ui","workflow","QUERY_PROCESSING")),
            "host",Map.of("type","micronaut-web","port",port,"openBrowser",false),"template",Map.of("id",templateId,"version","1","settings",Map.of("accent","#336699","showTimeline",false,"timelineLimit",12)));
        Path project=compileProject(new PipelineDefinition(base.name(),base.type(),base.schemaVersion(),base.nodes(),base.edges(),base.workflows(),capabilities));
        assertTrue(run(project,0,"--help").contains("serve"));
        assertTrue(Files.readString(project.resolve("run.bat")).contains("run.bat serve"));
        assertTrue(Files.readString(project.resolve("README.md")).contains("logs/server-0.log"));
        assertTrue(Files.readString(project.resolve("README.md")).contains("run.bat serve"));
        if(System.getProperty("os.name").startsWith("Windows")){
            Path batchOutput=temp.resolve("batch-help.log"),outside=Files.createDirectories(temp.resolve("outside project"));
            Process help=new ProcessBuilder("cmd.exe","/d","/c","call",project.resolve("run.bat").toString()).directory(outside.toFile()).redirectErrorStream(true).redirectOutput(batchOutput.toFile()).start();
            assertTrue(help.waitFor(10,TimeUnit.SECONDS));assertEquals(0,help.exitValue(),Files.readString(batchOutput));assertTrue(Files.readString(batchOutput).contains("run.bat serve"));
        }
        Files.createDirectories(project.resolve("data/ingestion"));Files.writeString(project.resolve("data/ingestion/sample.txt"),"sample");
        Path log=temp.resolve("web-host.log");
        Process server=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-jar","target/combined-1.0.0.jar","serve").directory(project.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try{
            var client=java.net.http.HttpClient.newHttpClient();String root="http://127.0.0.1:"+port;
            boolean ready=false;String readiness="No response";long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(45);
            while(System.nanoTime()<deadline){if(!server.isAlive())fail(Files.readString(log));try{var response=get(client,root+"/api/v1/application");readiness="HTTP "+response.statusCode();if(response.statusCode()==200){ready=true;break;}}catch(Exception error){readiness=error.toString();}Thread.sleep(100);}
            assertTrue(ready,readiness+"\n"+Files.readString(log));
            assertEquals(200,get(client,root+"/ui/monitor.html").statusCode());
            assertEquals(200,get(client,root+"/ui/template-bridge.js").statusCode());
            var json=new com.fasterxml.jackson.databind.ObjectMapper();
            if(!theme.equals("water")){
                var html=get(client,root+"/ui/templates/"+templateId+"/index.html");assertEquals(200,html.statusCode());assertTrue(html.body().contains("data-pipeline-inspector"));assertTrue(html.body().contains("/ui/template-bridge.js"));
                assertEquals(200,get(client,root+"/ui/templates/"+templateId+"/styles.css").statusCode());
                assertTrue(get(client,root+"/api/v1/templates").body().contains("settingsSchema"));
            }

            for(String mode:List.of("ingestion","query")){
                String body=json.writeValueAsString(Map.of("mode",mode,"input",mode.equals("ingestion")?"sample.txt":"sample question"));
                var response=client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(root+"/api/v1/executions")).header("Content-Type","application/json").POST(java.net.http.HttpRequest.BodyPublishers.ofString(body)).build(),java.net.http.HttpResponse.BodyHandlers.ofString());
                assertEquals(202,response.statusCode(),response.body());String id=(String)json.readValue(response.body(),Map.class).get("executionId");
                Map<?,?> state=Map.of();for(int i=0;i<100;i++){state=json.readValue(get(client,root+"/api/v1/executions/"+id+"/monitor").body(),Map.class);if("SUCCEEDED".equals(state.get("status")))break;Thread.sleep(50);}
                assertEquals("SUCCEEDED",state.get("status"));assertEquals(templateId,((Map<?,?>)state.get("template")).get("id"));assertEquals(false,((Map<?,?>)((Map<?,?>)state.get("template")).get("settings")).get("showTimeline"));
                assertFalse(((List<?>)state.get("events")).isEmpty());
                var resultResponse=get(client,root+"/api/v1/executions/"+id+"/result");
                assertEquals(200,resultResponse.statusCode());assertEquals("no-store",resultResponse.headers().firstValue("cache-control").orElseThrow());
                var result=json.readValue(resultResponse.body(),Map.class);
                if(mode.equals("query")){
                    assertEquals(true,result.get("available"));assertEquals("AnswerResult",result.get("type"));
                    assertEquals("UAE answer <script> rendered as text",result.get("answer"));assertEquals("sample question",result.get("query"));
                    assertEquals("Middle East.pdf",((Map<?,?>)((List<?>)result.get("citations")).getFirst()).get("sourceName"));
                    assertFalse(resultResponse.body().contains("private source text"));assertFalse(state.toString().contains("UAE answer"));
                }else assertEquals(false,result.get("available"));
                if(mode.equals("query"))assertTrue(((List<Map<String,Object>>)state.get("events")).stream().anyMatch(e->"fixture.query".equals(e.get("code"))));
            }
            var historyResponse=get(client,root+"/api/v1/executions");
            assertFalse(historyResponse.body().contains("UAE answer"));assertFalse(historyResponse.body().contains("private source text"));
            assertEquals(2,json.readValue(get(client,root+"/api/v1/executions").body(),List.class).size());
            Path structuredLog=project.resolve("logs/server-0.log");assertTrue(Files.isRegularFile(structuredLog));
            String serverLog=Files.readString(structuredLog);assertTrue(serverLog.contains("Pipeline web app ready"));assertTrue(serverLog.contains("RUN_STARTED"));assertTrue(serverLog.contains("RUN_SUCCEEDED"));assertTrue(serverLog.contains("node="));assertFalse(serverLog.contains("UAE answer"));assertFalse(serverLog.contains("private source text"));
            var rejected=client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(root+"/api/v1/executions")).header("Content-Type","application/json").POST(java.net.http.HttpRequest.BodyPublishers.ofString("{\"mode\":\"ingestion\",\"input\":\"../outside.txt\"}")).build(),java.net.http.HttpResponse.BodyHandlers.ofString());
            assertEquals(400,rejected.statusCode());
        }finally{server.destroy();if(!server.waitFor(5,TimeUnit.SECONDS)){server.destroyForcibly();assertTrue(server.waitFor(5,TimeUnit.SECONDS));}releaseServerFiles(project);}
    }
    // Windows can report process termination before its mapped JAR/log handles are released.
    // Delete only generated runtime files, with a bounded retry, before JUnit removes the temp tree.
    private void releaseServerFiles(Path project)throws Exception{
        assertTrue(project.toRealPath().startsWith(temp.toRealPath()));
        List<Path> files=new ArrayList<>();
        try(var jars=Files.list(project.resolve("lib"))){files.addAll(jars.filter(Files::isRegularFile).toList());}
        Path logs=project.resolve("logs");if(Files.isDirectory(logs))try(var entries=Files.list(logs)){files.addAll(entries.filter(Files::isRegularFile).toList());}
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        for(Path file:files)for(;;)try{Files.deleteIfExists(file);break;}catch(java.nio.file.FileSystemException failure){if(System.nanoTime()>=deadline)throw failure;Thread.sleep(50);}
    }
    private java.net.http.HttpResponse<String> get(java.net.http.HttpClient client,String url)throws Exception{return client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(url)).timeout(java.time.Duration.ofSeconds(3)).GET().build(),java.net.http.HttpResponse.BodyHandlers.ofString());}

    private Path compileProject(PipelineDefinition definition) throws Exception {
        PluginCatalog catalog=fixtures();
        var generator=new PipelineProjectGenerator(new PipelineService(catalog),catalog,new MavenProjectBuilder("","",60),new TemplateCatalog(temp.resolve("templates").toString()));
        Path project=generator.createProject(definition,"Combined","jar",temp.resolve("generated-"+UUID.randomUUID()));
        Path classes=project.resolve("target/classes");Files.createDirectories(classes);
        String classpath;
        try(var libraries=Files.list(project.resolve("lib"))){classpath=String.join(java.io.File.pathSeparator,libraries.map(Path::toString).toList());}
        List<String> args=new ArrayList<>(List.of("--release","21","-proc:none","-classpath",classpath,"-d",classes.toString()));
        try(var sources=Files.list(project.resolve("src/main/java/generated/pipeline"))){sources.forEach(source->args.add(source.toString()));}
        assertEquals(0,ToolProvider.getSystemJavaCompiler().run(null,null,null,args.toArray(String[]::new)));
        try(var resources=Files.walk(project.resolve("src/main/resources"))){for(Path file:resources.filter(Files::isRegularFile).toList()){Path target=classes.resolve(project.resolve("src/main/resources").relativize(file));Files.createDirectories(target.getParent());Files.copy(file,target);}}
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
            for(int i=0;i<600;i++){snapshot=manager.value.get(accepted.executionId()).orElseThrow();if(snapshot.status()==ExecutionStatus.SUCCEEDED||snapshot.status()==ExecutionStatus.FAILED)break;Thread.sleep(50);}
            assertEquals(ExecutionStatus.SUCCEEDED,snapshot.status(),snapshot.error());assertEquals(PipelineType.QUERY_PROCESSING,snapshot.workflow());
            assertTrue(manager.value.events(accepted.executionId()).stream().noneMatch(event->"ingest".equals(event.nodeId())));
        }
    }
    private record ExecutionManagerResource(GraphExecutionManager value) implements AutoCloseable {public void close(){value.close();}}

    private String run(Path project,int expected,String... flags)throws Exception{return run(project,expected,Map.of(),flags);}
    private String run(Path project,int expected,Map<String,String> environment,String... flags)throws Exception{
        List<String> command=new ArrayList<>(List.of(Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString(),"-jar","target/combined-1.0.0.jar"));command.addAll(List.of(flags));command.add("--non-interactive");
        Path outputFile=temp.resolve("process-output-"+UUID.randomUUID()+".log");
        ProcessBuilder builder=new ProcessBuilder(command).directory(project.toFile()).redirectErrorStream(true).redirectOutput(outputFile.toFile());
        builder.environment().remove("GENERATED_TEST_VALUE");builder.environment().putAll(environment);Process process=builder.start();
        try{
            assertTrue(process.waitFor(60,TimeUnit.SECONDS),"Generated CLI timed out: "+outputFile);
            String output=Files.readString(outputFile);assertEquals(expected,process.exitValue(),output);return output;
        }finally{
            if(process.isAlive()){process.destroy();if(!process.waitFor(5,TimeUnit.SECONDS)){process.destroyForcibly();assertTrue(process.waitFor(5,TimeUnit.SECONDS));}}
        }
    }
    private PipelineDefinition project(){return new PipelineDefinition("Combined RAG",PipelineType.RAG_PROJECT,2,List.of(),List.of(),Map.of(
        PipelineType.DOCUMENT_INGESTION,new PipelineWorkflow(List.of(new PipelineNode("ingest","fixture-ingest","1.0.0",Map.of("prefix","ingest-value"))),List.of()),
        PipelineType.QUERY_PROCESSING,new PipelineWorkflow(List.of(new PipelineNode("query","fixture-query","1.0.0",Map.of("prefix","query-value","count",7))),List.of())));}
    private PluginCatalog fixtures()throws Exception{
        Path root=temp.resolve("plugins");Files.createDirectories(root);
        Path core=new PluginCatalog("").pipelineCoreJar(),copy=temp.resolve("pipeline-core/target/pipeline-core.jar");Files.createDirectories(copy.getParent());Files.copy(core,copy,StandardCopyOption.REPLACE_EXISTING);
        Path web=core.getParent().getParent().getParent().resolve("pipeline-web/target/pipeline-web.jar"),webCopy=temp.resolve("pipeline-web/target/pipeline-web.jar");if(Files.exists(web)){Files.createDirectories(webCopy.getParent());Files.copy(web,webCopy,StandardCopyOption.REPLACE_EXISTING);}
        fixture(root,"fixture-ingest","Fixture ingestion",IngestFixture.class,"File",PipelineType.DOCUMENT_INGESTION);
        fixture(root,"fixture-query","Fixture query",QueryFixture.class,"SearchRequest",PipelineType.QUERY_PROCESSING);
        fixture(root,"fixture-answer","Fixture answer",AnswerFixture.class,"SearchRequest",PipelineType.QUERY_PROCESSING);
        fixture(root,"fixture-graph","Fixture graph",GraphFixture.class,"File",PipelineType.DOCUMENT_INGESTION);
        return new PluginCatalog(root.toString());
    }
    private void fixture(Path root,String id,String name,Class<?> type,String input,PipelineType workflow)throws Exception{
        Path module=root.resolve(id),metadata=module.resolve("src/main/resources/META-INF/PluginManifest.yaml");Files.createDirectories(metadata.getParent());
        String yaml="plugin:\n  id: "+id+"\n  name: "+name+"\n  version: 1.0.0\n  apiVersion: '1.0'\n  entrypoint: "+type.getName()+"\n  workflows: ["+workflow+"]\n  input: {type: "+input+"}\n  output: {type: "+(type==AnswerFixture.class?"AnswerResult":type==GraphFixture.class?"ExtractedGraph":"String")+"}\n  configuration:\n    properties:\n      prefix: {type: string}\n      count: {type: integer}\n      OLLAMA_TIMEOUT_SECONDS: {type: integer}\n      secretValue: {type: string, format: secret-reference, sensitive: true}\n      failWithSecret: {type: boolean}\n";
        if(type==QueryFixture.class||type==AnswerFixture.class)yaml+="  capabilities: [messages]\n";
        Files.writeString(metadata,yaml);Files.writeString(module.resolve("pom.xml"),"<project><artifactId>"+id+"</artifactId></project>");Files.createDirectories(module.resolve("target"));
        try(var jar=new JarOutputStream(Files.newOutputStream(module.resolve("target/"+id+".jar")))){
            jar.putNextEntry(new JarEntry("META-INF/PluginManifest.yaml"));jar.write(yaml.getBytes(java.nio.charset.StandardCharsets.UTF_8));jar.closeEntry();
            String resource=type.getName().replace('.','/')+".class";jar.putNextEntry(new JarEntry(resource));try(var stream=type.getClassLoader().getResourceAsStream(resource)){assertNotNull(stream);stream.transferTo(jar);}jar.closeEntry();
        }
    }
}
