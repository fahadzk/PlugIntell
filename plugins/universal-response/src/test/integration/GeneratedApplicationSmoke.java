package com.core.docintel.pipeline.builder;
import com.core.docintel.pipeline.api.*;
import com.core.docintel.pipeline.core.PluginLoader;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import java.util.regex.*;
import java.net.*;
import java.net.http.*;
import javax.tools.ToolProvider;

/** Offline end-to-end check using the real renderer JAR and actual Studio project generator. */
public final class GeneratedApplicationSmoke {
    static void check(boolean condition,String message){if(!condition)throw new IllegalStateException(message);}
    static void copy(Path from,Path to)throws Exception{Files.createDirectories(to.getParent());Files.copy(from,to,StandardCopyOption.REPLACE_EXISTING);}
    public static void main(String[] args)throws Exception{
        Path repository=Path.of(".").toAbsolutePath().normalize(),module=repository.resolve("plugins/universal-response");
        Path renderer=module.resolve("target/universal-response-plugin.jar");check(Files.isRegularFile(renderer),"Build the renderer first.");
        var client=HttpClient.newHttpClient();List<String> urls=new ArrayList<>();
        var capture=(com.core.docintel.pipeline.api.PluginMessages)(message->{if(message.code().equals("response.viewer.available")){var parts=(List<?>)message.payload().get("viewerIdBytes");byte[] bytes=new byte[16];for(int i=0;i<16;i++)bytes[i]=((Number)parts.get(i)).byteValue();var buffer=java.nio.ByteBuffer.wrap(bytes);urls.add("http://127.0.0.1:"+message.payload().get("viewerPort")+"/responses/"+new UUID(buffer.getLong(),buffer.getLong()));}});
        try{
            Plugin first,second;try(var loader=new PluginLoader()){first=loader.load(renderer,capture);((ConfigurablePlugin)first).configure(Map.of("UR_PORT",0,"UR_AUTO_OPEN_BROWSER",false));first.execute(new AnswerResult("First Studio run","Hello",false,List.of()));((ManagedPlugin)first).close();}
            try(var loader=new PluginLoader()){second=loader.load(renderer,capture);((ConfigurablePlugin)second).configure(Map.of("UR_PORT",0,"UR_AUTO_OPEN_BROWSER",false));second.execute(new AnswerResult("Second Studio run","Hello again",false,List.of()));((ManagedPlugin)second).close();}
            check(first.getClass()==second.getClass(),"Studio executions must share the application implementation classloader.");check(urls.size()==2,"Both queries need viewer URLs.");check(URI.create(urls.get(0)).getPort()==URI.create(urls.get(1)).getPort(),"Queries must use the same embedded server.");
            for(String url:urls)check(client.send(HttpRequest.newBuilder(URI.create(url)).build(),HttpResponse.BodyHandlers.ofString()).statusCode()==200,"Viewer must survive query and loader close.");
        }finally{PluginLoader.closeApplicationPlugins();}
        Path workspace=module.resolve("target/generated-smoke-"+UUID.randomUUID()),plugins=workspace.resolve("plugins");
        copy(repository.resolve("pipeline-core/target/pipeline-core.jar"),workspace.resolve("pipeline-core/target/pipeline-core.jar"));
        copy(renderer,plugins.resolve("universal-response/target/universal-response-plugin.jar"));copy(module.resolve("src/main/resources/META-INF/PluginManifest.yaml"),plugins.resolve("universal-response/src/main/resources/META-INF/PluginManifest.yaml"));copy(module.resolve("pom.xml"),plugins.resolve("universal-response/pom.xml"));
        Path answer=plugins.resolve("offline-answer"),source=workspace.resolve("fixture-src/demo/OfflineAnswer.java"),classes=workspace.resolve("fixture-classes");Files.createDirectories(source.getParent());Files.createDirectories(classes);
        Files.writeString(source,"""
            package demo;
            import com.core.docintel.pipeline.api.*;
            import java.util.*;
            public final class OfflineAnswer extends AbstractPlugin {
              public OfflineAnswer(){} public OfflineAnswer(PluginMessages messages){super(messages);}
              public PluginManifest manifest(){return new PluginManifest("offline-answer","Offline answer","1.0.0","1.0",getClass().getName(),"SearchRequest","AnswerResult");}
              public Object execute(Object input){return new AnswerResult(((SearchRequest)input).query(),"## Generated response\\n\\nVerified offline.\\n\\n```json\\n[{\\\"month\\\":\\\"Jan\\\",\\\"revenue\\\":100}]\\n```",true,List.of(new RetrievedChunk("s","PRIVATE_RETRIEVAL_CONTEXT","fixture.txt",1,1)));}
            }
            """.replace("\\\\n","\\n"));
        check(ToolProvider.getSystemJavaCompiler().run(null,null,null,"--release","21","-proc:none","-classpath",repository.resolve("pipeline-core/target/pipeline-core.jar").toString(),"-d",classes.toString(),source.toString())==0,"Offline answer fixture must compile.");
        String yaml="plugin:\n  id: offline-answer\n  name: Offline answer\n  version: 1.0.0\n  apiVersion: '1.0'\n  entrypoint: demo.OfflineAnswer\n  workflows: [QUERY_PROCESSING]\n  capabilities: [messages]\n  input: {type: SearchRequest}\n  output: {type: AnswerResult}\n";
        Files.createDirectories(answer.resolve("src/main/resources/META-INF"));Files.writeString(answer.resolve("src/main/resources/META-INF/PluginManifest.yaml"),yaml);Files.writeString(answer.resolve("pom.xml"),"<project><artifactId>offline-answer-plugin</artifactId></project>");Files.createDirectories(answer.resolve("target"));
        try(var out=new JarOutputStream(Files.newOutputStream(answer.resolve("target/offline-answer-plugin.jar")))){out.putNextEntry(new JarEntry("META-INF/PluginManifest.yaml"));out.write(yaml.getBytes(java.nio.charset.StandardCharsets.UTF_8));out.closeEntry();out.putNextEntry(new JarEntry("demo/OfflineAnswer.class"));Files.copy(classes.resolve("demo/OfflineAnswer.class"),out);out.closeEntry();}
        var catalog=new PluginCatalog(plugins.toString());var definition=new PipelineDefinition("Response smoke",PipelineType.QUERY_PROCESSING,1,List.of(new PipelineNode("answer","offline-answer","1.0.0",Map.of()),new PipelineNode("view","universal-response","1.0.0",Map.of("UR_PORT",0,"UR_AUTO_OPEN_BROWSER",false))),List.of(new PipelineEdge("answer","view")));
        var validation=new GraphPipelineValidator().validate(definition,catalog.all(),true);check(validation.valid(),validation.errors().toString());
        var generator=new PipelineProjectGenerator(new PipelineService(catalog),catalog,new MavenProjectBuilder("","",60),new TemplateCatalog(workspace.resolve("templates").toString()));Path project=generator.createProject(definition,"ResponseSmoke","jar",workspace.resolve("app"));Path appClasses=project.resolve("target/classes");Files.createDirectories(appClasses);
        String cp;try(var files=Files.list(project.resolve("lib"))){cp=String.join(java.io.File.pathSeparator,files.map(Path::toString).toList());}
        List<String> compile=new ArrayList<>(List.of("--release","21","-proc:none","-classpath",cp,"-d",appClasses.toString()));try(var files=Files.list(project.resolve("src/main/java/generated/pipeline"))){files.forEach(path->compile.add(path.toString()));}check(ToolProvider.getSystemJavaCompiler().run(null,null,null,compile.toArray(String[]::new))==0,"Generated application must compile.");
        check(!Files.exists(project.resolve("lib/pipeline-web.jar")),"Renderer must not force the Micronaut host.");
        try(var jar=new java.util.zip.ZipFile(project.resolve("lib/universal-response-plugin.jar").toFile())){check(jar.getEntry("response-ui/index.html")!=null&&jar.getEntry("response-ui/renderers.js")!=null,"UI resources must be bundled.");check(jar.getEntry("com/core/docintel/pipeline/api/AnswerResult.class")==null,"Shared API must not be duplicated in the plugin.");}
        Manifest manifest=new Manifest();manifest.getMainAttributes().putValue("Manifest-Version","1.0");manifest.getMainAttributes().putValue("Main-Class","generated.pipeline.GeneratedPipelineMain");String pom=Files.readString(project.resolve("pom.xml"));manifest.getMainAttributes().putValue("Class-Path",pom.split("<Class-Path>")[1].split("</Class-Path>")[0]);
        try(var out=new JarOutputStream(Files.newOutputStream(project.resolve("target/responsesmoke-1.0.0.jar")),manifest);var files=Files.walk(appClasses)){for(Path file:files.filter(Files::isRegularFile).toList()){out.putNextEntry(new JarEntry(appClasses.relativize(file).toString().replace('\\','/')));Files.copy(file,out);out.closeEntry();}}
        Path log=workspace.resolve("generated-cli.log");Process process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-Djava.awt.headless=true","-jar","target/responsesmoke-1.0.0.jar","--mode","query","--query","Generated renderer check","--non-interactive","--citations","yes").directory(project.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try{
            String viewer=null;for(int i=0;i<100;i++){if(Files.exists(log)){var match=Pattern.compile("http://127.0.0.1:[0-9]+/responses/[a-f0-9-]{36}").matcher(Files.readString(log));if(match.find()){viewer=match.group();break;}}if(!process.isAlive())break;Thread.sleep(100);}
            check(viewer!=null,"Generated query must publish a viewer URL. See "+log);check(process.isAlive(),"Embedded viewer must remain alive after pipeline completion.");String base=URI.create(viewer).resolve("/").toString();
            var data=client.send(HttpRequest.newBuilder(URI.create(base+"api/responses/latest")).build(),HttpResponse.BodyHandlers.ofString());check(data.statusCode()==200,"Published response must be available.");check(data.body().contains("Generated renderer check"),"Query metadata must be retained.");check(!data.body().contains("PRIVATE_RETRIEVAL_CONTEXT"),"Retrieved context must not leak.");check(data.body().contains("table"),"Structured answer block must be detected.");
            check(client.send(HttpRequest.newBuilder(URI.create(viewer)).build(),HttpResponse.BodyHandlers.ofString()).body().contains("Response Studio"),"Browser UI must be served.");System.out.println("PASS: repeated Studio loads, generated query JAR, original result pass-through, bundled assets, no separate UI host, viewer lifetime and context privacy. Project: "+project);
        }finally{process.destroy();if(!process.waitFor(10,java.util.concurrent.TimeUnit.SECONDS))process.destroyForcibly();PluginLoader.closeApplicationPlugins();}
    }
}
