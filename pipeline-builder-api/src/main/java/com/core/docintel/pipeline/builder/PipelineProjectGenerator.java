package com.core.docintel.pipeline.builder;

import jakarta.inject.Singleton;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

@Singleton
public final class PipelineProjectGenerator {
    private final PipelineService service;
    private final PluginCatalog catalog;
    private final MavenProjectBuilder mavenBuilder;
    private final TemplateCatalog templates;
    @jakarta.inject.Inject public PipelineProjectGenerator(PipelineService service, PluginCatalog catalog, MavenProjectBuilder mavenBuilder,TemplateCatalog templates) { this.service=service; this.catalog=catalog; this.mavenBuilder=mavenBuilder;this.templates=templates; }
    public PipelineProjectGenerator(PipelineService service,PluginCatalog catalog,MavenProjectBuilder mavenBuilder){this(service,catalog,mavenBuilder,new TemplateCatalog("./data/templates"));}

    /** Creates a durable project containing both independently runnable workflow lanes. */
    public Path createProject(PipelineDefinition definition,String projectName,String format,Path destination) {
        return createProject(definition,projectName,format,destination,null,null);
    }
    public Path createProject(PipelineDefinition definition,String projectName,String format,Path destination,String pipelineId,Integer pipelineVersion) {
        CapabilitySettings.validate(definition,true);
        templates.validatePin(definition.capabilities());
        if(ProjectCapabilities.kafka(definition)&&!com.core.docintel.pipeline.kafka.KafkaEventAdapter.available())throw new IllegalArgumentException("Kafka support is not packaged; rebuild Studio with -Pkafka.");
        if("native-image".equalsIgnoreCase(format)&&ProjectCapabilities.kafka(definition))throw new IllegalArgumentException("Kafka telemetry native builds are not supported; use JVM JAR for Kafka.");

        String name=safeName(projectName);
        Map<PipelineType,List<PipelineNode>> lanes=new LinkedHashMap<>();
        Map<String,PluginInfo> unique=new LinkedHashMap<>();
        for(PipelineType type:List.of(PipelineType.DOCUMENT_INGESTION,PipelineType.QUERY_PROCESSING))if(definition.hasWorkflow(type)){
            PipelineDefinition graph=definition.workflow(type);
            PipelineValidation validation=service.validate(ProjectBuildManager.chainRequest(graph,name,format));
            if(!validation.valid()||!validation.generationSupported())throw new IllegalArgumentException(String.join("; ",validation.errors())+validation.generationMessage());
            lanes.put(type,ProjectBuildManager.orderedNodes(graph));
            validation.plugins().forEach(plugin->unique.put(plugin.id(),plugin));
        }
        if(lanes.isEmpty())throw new IllegalArgumentException("Project needs at least one workflow.");
        Set<String> secretVariables=new TreeSet<>();collectSecretVariables(definition.capabilities(),secretVariables);lanes.values().stream().flatMap(Collection::stream).map(PipelineNode::config).filter(Objects::nonNull).forEach(config->config.values().forEach(value->collectSecretVariables(value,secretVariables)));
        List<PluginInfo> plugins=List.copyOf(unique.values());
        PipelineRequest request=new PipelineRequest(name,List.copyOf(unique.keySet()),format,Map.of());
        Path dir=destination.toAbsolutePath().normalize();
        try {
            validateDestination(destination);
            Path source=dir.resolve("src/main/java/generated/pipeline");Files.createDirectories(source);Files.createDirectories(dir.resolve("lib"));
            Files.copy(catalog.pipelineCoreJar(),dir.resolve("lib/pipeline-core.jar"));
            for(PluginInfo plugin:plugins)Files.copy(catalog.pluginJar(plugin),dir.resolve("lib/"+plugin.artifactId()+".jar"));
            List<String> libraries=ProjectCapabilities.libraries(definition);
            for(String library:libraries){Path jar=catalog.pipelineCoreJar().getParent().getParent().getParent().resolve(library+"/target/"+library+".jar");if(!Files.isRegularFile(jar))throw new IllegalArgumentException("Build the selected capability library first: "+jar);Files.copy(jar,dir.resolve("lib/"+library+".jar"));}
            try (var jars=Files.list(dir.resolve("lib"))) { GeneratedLibraryValidation.validateNetty(jars.filter(path->path.toString().endsWith(".jar")).toList()); }
            write(dir.resolve("pom.xml"),pom(request,plugins,libraries));
            write(source.resolve("GeneratedPipelineMain.java"),ProjectCapabilities.main(definition,secretVariables));
            write(source.resolve("RuntimeSupport.java"),runtimeSource());
            write(source.resolve("PipelineAssembly.java"),ProjectCapabilities.assembly(definition,lanes,unique,pipelineId,pipelineVersion));
            Path resources=dir.resolve("src/main/resources");Files.createDirectories(resources);
            Map<String,Object> descriptor=new LinkedHashMap<>();descriptor.put("schemaVersion",1);descriptor.put("name",definition.name());descriptor.put("pipelineId",pipelineId);descriptor.put("pipelineVersion",pipelineVersion);descriptor.put("capabilities",definition.capabilities()==null?Map.of():definition.capabilities());
            Map<String,Object> workflowDescriptors=new LinkedHashMap<>();
            for(var lane:lanes.entrySet()){PipelineType type=lane.getKey();var nodes=lane.getValue().stream().map(node->{PluginInfo plugin=unique.get(node.pluginId());return new com.core.docintel.pipeline.events.PipelineDescriptor.Node(node.id(),node.pluginId(),plugin.name(),plugin.capabilities());}).toList();var edges=definition.workflow(type).edges().stream().map(edge->new com.core.docintel.pipeline.events.PipelineDescriptor.Edge(edge.from(),edge.to())).toList();workflowDescriptors.put(type==PipelineType.DOCUMENT_INGESTION?"ingestion":"query",new com.core.docintel.pipeline.events.PipelineDescriptor(pipelineId,pipelineVersion,type.name(),nodes,edges).toMap());}
            descriptor.put("workflows",workflowDescriptors);
            write(resources.resolve("pipeline-descriptor.json"),com.core.docintel.pipeline.events.EventJson.write(descriptor));
            if(ProjectCapabilities.web(definition)){
                Path assets=resources.resolve("public");Files.createDirectories(assets);
                for(String asset:List.of("monitor.html","monitor.css","monitor.js","template-bridge.js"))try(InputStream stream=getClass().getResourceAsStream("/public/"+asset)){if(stream==null)throw new IllegalArgumentException("Monitor asset is missing: "+asset);Files.copy(stream,assets.resolve(asset));}
                String template=CapabilitySettings.text(CapabilitySettings.map(definition.capabilities().get("template")),"id","standard");
                if(!Set.of("standard","water").contains(template))templates.get(template).copyTo(assets.resolve("templates").resolve(template));
            }
            for(var lane:lanes.entrySet())write(source.resolve(className(lane.getKey())+".java"),ProjectCapabilities.workflow(lane.getKey(),lane.getValue(),unique));
            write(dir.resolve("README.md"),readme(name,request,plugins,definition)+"\n## Event delivery\n\nTelemetry destinations use bounded queues and best-effort delivery. Monitor history is memory-only: 100 runs and 5,000 events per run. Kafka uses JSON schema version 1 and is not exactly-once. Credentials remain environment references.\n");
            write(dir.resolve("run.bat"),GeneratedLaunchFiles.batch(name,definition,"native-image".equalsIgnoreCase(format)));
            write(dir.resolve("run.sh"),GeneratedLaunchFiles.shell(name,definition,"native-image".equalsIgnoreCase(format)));
            write(dir.resolve(".gitignore"),"target/\n.env\nlogs/\n");
            write(dir.resolve(".env.example"),envExample(plugins)+"\n# Referenced secrets: set locally before running.\n"+String.join("\n",secretVariables.stream().map(variable->variable+"=").toList())+"\n"+(ProjectCapabilities.web(definition)?"PIPELINE_INPUT_ROOT=./data/ingestion\nPIPELINE_LOG_DIR=./logs\n":""));
            write(dir.resolve("pipeline-env.bat"),envFile(plugins,Map.of(),false));
            write(dir.resolve("pipeline-env.ps1"),envFile(plugins,Map.of(),true));
            return dir;
        }catch(IOException e){throw new IllegalStateException("Could not create project: "+e.getMessage(),e);}
    }

    static void validateDestination(Path destination) throws IOException {
        if (!destination.isAbsolute()) throw new IllegalArgumentException("Project location must be an absolute path on the backend machine.");
        Path dir=destination.normalize();
        if (dir.getParent()==null) throw new IllegalArgumentException("Choose a project folder below a drive or filesystem root.");
        for (Path current=dir;current!=null;current=current.getParent()) {
            if (!Files.exists(current,LinkOption.NOFOLLOW_LINKS)) continue;
            var attributes=Files.readAttributes(current,java.nio.file.attribute.BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
            if (attributes.isSymbolicLink() || attributes.isOther()) throw new IllegalArgumentException("Project location and its parents cannot be symbolic links or junctions.");
            if (!attributes.isDirectory()) throw new IllegalArgumentException("Project location and its parents must be directories.");
        }
        if (Files.exists(dir,LinkOption.NOFOLLOW_LINKS)) try (var entries=Files.list(dir)) {
            if (entries.findAny().isPresent()) throw new IllegalArgumentException("Project location must be an empty directory.");
        }
    }

    private static String pom(PipelineRequest r,List<PluginInfo> ps,List<String> libraries) {
        StringBuilder b=new StringBuilder("""
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
                  <modelVersion>4.0.0</modelVersion><groupId>generated.pipeline</groupId><artifactId>""").append(xml(safeName(r.projectName()).toLowerCase(Locale.ROOT))).append("</artifactId><version>1.0.0</version>\n")
                .append("<properties><maven.compiler.release>21</maven.compiler.release><project.build.sourceEncoding>UTF-8</project.build.sourceEncoding></properties><dependencies>\n")
                .append(localJarDep("pipeline-core", "pipeline-core.jar"));
        for(PluginInfo p:ps)b.append(localJarDep(p.artifactId(), p.artifactId()+".jar"));
        for(String library:libraries)b.append(localJarDep(library,library+".jar"));
        StringBuilder classPath = new StringBuilder("../lib/pipeline-core.jar");
        for(String library:libraries)classPath.append(" ../lib/").append(library).append(".jar");
        for (PluginInfo p : ps) classPath.append(' ').append("../lib/").append(p.artifactId()).append(".jar");
        b.append("</dependencies><build><plugins><plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-compiler-plugin</artifactId><version>3.14.0</version><configuration><release>21</release><proc>none</proc></configuration></plugin><plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-jar-plugin</artifactId><version>3.4.1</version><configuration><archive><manifest><mainClass>generated.pipeline.GeneratedPipelineMain</mainClass></manifest><manifestEntries><Class-Path>").append(classPath).append("</Class-Path></manifestEntries></archive></configuration></plugin></plugins></build>");
        if("native-image".equalsIgnoreCase(r.outputFormat())) { b.append("<profiles><profile><id>native</id><build><plugins><plugin><groupId>org.graalvm.buildtools</groupId><artifactId>native-maven-plugin</artifactId><version>0.10.6</version><extensions>true</extensions><configuration><useArgFile>true</useArgFile><mainClass>generated.pipeline.GeneratedPipelineMain</mainClass><imageName>").append(xml(safeName(r.projectName()))).append("</imageName><buildArgs><buildArg>--no-fallback</buildArg>");
            // Netty allocators, SSL state and logging providers must use the runtime environment.
            if(libraries.contains("pipeline-web"))b.append("<buildArg>--initialize-at-run-time=io.netty,org.slf4j</buildArg>");
            b.append("</buildArgs><classpath><param>${project.build.outputDirectory}</param><param>${project.basedir}/lib/pipeline-core.jar</param>");
            for(String library:libraries)b.append("<param>${project.basedir}/lib/").append(xml(library)).append(".jar</param>");
            for(PluginInfo plugin:ps)b.append("<param>${project.basedir}/lib/").append(xml(plugin.artifactId())).append(".jar</param>");
            b.append("</classpath></configuration><executions><execution><phase>package</phase><goals><goal>compile-no-fork</goal></goals></execution></executions></plugin></plugins></build></profile></profiles>"); }
        return b.append("</project>").toString();
    }
    private static String localJarDep(String artifact,String file){return "<dependency><groupId>generated.pipeline</groupId><artifactId>"+xml(artifact)+"</artifactId><version>1.0.0</version><scope>system</scope><systemPath>${project.basedir}/lib/"+xml(file)+"</systemPath></dependency>\n";}
    private static String className(PipelineType type){return type==PipelineType.DOCUMENT_INGESTION?"IngestionPipeline":"QueryPipeline";}
    private static String mainSource(Set<PipelineType> workflows,Set<String> secretVariables){
        StringBuilder source=new StringBuilder("""
                package generated.pipeline;
                import java.util.*;
                public final class GeneratedPipelineMain {
                  static {RuntimeSupport.registerSecretNames(Set.of(SECRET_NAMES));}
                  public static void main(String[] args) {
                    boolean debug=false;
                    try {
                      Map<String,String> flags=new HashMap<>();
                      for(int i=0;i<args.length;i++){
                        String flag=args[i];
                        if(flag.equals("--help")){System.out.println("Usage: --mode ingestion --input <file> | --mode query --query <question> [--debug]");return;}
                        if(flag.equals("--debug")){if(debug)throw new IllegalArgumentException("Duplicate --debug flag.");debug=true;continue;}
                        if(!Set.of("--mode","--input","--query").contains(flag)||i+1>=args.length||flags.putIfAbsent(flag,args[++i])!=null)throw new IllegalArgumentException("Invalid, missing, or duplicate flag: "+flag);
                      }
                      String mode=flags.get("--mode");
                      if(mode==null)throw new IllegalArgumentException("--mode is required.");
                      if(debug)RuntimeSupport.printEnvironment();
                      Object result;
                      switch(mode){
                """);
        source.replace(source.indexOf("Set.of(SECRET_NAMES)"),source.indexOf("Set.of(SECRET_NAMES)")+"Set.of(SECRET_NAMES)".length(),"Set.of("+String.join(",",secretVariables.stream().map(PipelineProjectGenerator::javaValue).toList())+")");
        for(PipelineType type:workflows){boolean ingest=type==PipelineType.DOCUMENT_INGESTION;
            source.append("case ").append(javaValue(ingest?"ingestion":"query")).append(" -> { if(flags.containsKey(").append(javaValue(ingest?"--query":"--input")).append("))throw new IllegalArgumentException(\"Input flag does not match mode.\"); result=").append(className(type)).append(".run(RuntimeSupport.required(flags,").append(javaValue(ingest?"--input":"--query")).append(")); }\n");
        }
        return source.append("""
                        default -> throw new IllegalArgumentException("Selected workflow is not available in this project.");
                      }
                      System.out.println("Pipeline completed successfully. Result: "+result);
                    }catch(Exception error){RuntimeSupport.report(error,debug);System.exit(1);}
                  }
                }
                """).toString();
    }
    private static String workflowSource(PipelineType type,List<PipelineNode> nodes,Map<String,PluginInfo> plugins){
        StringBuilder source=new StringBuilder("package generated.pipeline;\nimport com.core.docintel.pipeline.api.*;\nimport com.core.docintel.pipeline.core.*;\nimport java.util.*;\npublic final class "+className(type)+" {\n public static Object run(String value){\n");
        if(type==PipelineType.DOCUMENT_INGESTION)source.append("java.io.File input=RuntimeSupport.inputFile(value);\n");
        source.append("List<Plugin> plugins=List.of(\n");
        for(int i=0;i<nodes.size();i++)source.append("new ").append(plugins.get(nodes.get(i).pluginId()).entrypoint()).append("()").append(i+1<nodes.size()?",\n":"\n");
        source.append(");\ntry(Pipeline pipeline=new Pipeline(plugins)){\n");
        for(int i=0;i<nodes.size();i++){
            Map<String,Object> config=new LinkedHashMap<>(nodes.get(i).config()==null?Map.of():nodes.get(i).config());config.remove("connectionRef");
            source.append("RuntimeSupport.configure(plugins.get(").append(i).append("),").append(javaValue(config)).append(");\n");
        }
        source.append("new PipelineValidator().validate(plugins);\nreturn pipeline.execute(");
        source.append(type==PipelineType.DOCUMENT_INGESTION?"input":"new SearchRequest(value,Boolean.parseBoolean(System.getenv().getOrDefault(\"RAG_SEARCH_INCLUDE_CITATIONS\",\"true\")),Integer.parseInt(System.getenv().getOrDefault(\"RAG_SEARCH_TOP_K\",\"5\")))");
        return source.append(");\n}\n}\n}\n").toString();
    }
    private static String runtimeSource(){return """
            package generated.pipeline;
            import com.core.docintel.pipeline.api.*;
            import java.util.*;
            import java.io.*;
            final class RuntimeSupport {
              private static final Set<String> secrets=java.util.concurrent.ConcurrentHashMap.newKeySet();
              private static final Set<String> secretNames=java.util.concurrent.ConcurrentHashMap.newKeySet();
              private static final String SENSITIVE_NAME=".*(PASSWORD|PASSWD|PASS|PWD|TOKEN|SECRET|KEY|CREDENTIAL|AUTH|PRIVATE|COOKIE|SESSION|CERT|BEARER|PAT|JWT).*";
              static {System.getenv().forEach((name,value)->{if(name.toUpperCase(Locale.ROOT).matches(SENSITIVE_NAME))register(value);});}
              private static void register(String value){if(value!=null&&!value.isEmpty())secrets.add(value);}
              static void registerSecretNames(Set<String> names){secretNames.addAll(names);names.forEach(name->register(System.getenv(name)));}
              private static String redact(String value){String result=value==null?"":value;for(String secret:secrets.stream().sorted(Comparator.comparingInt(String::length).reversed()).toList())result=result.replace(secret,"[REDACTED]");return result.replaceAll("(?i)(://[^:/@\\s]+):[^@/\\s]+@","$1:[REDACTED]@");}
              static void printEnvironment(){System.err.println("Environment variables (sensitive values redacted):");new TreeMap<>(System.getenv()).forEach((name,value)->System.err.println("  "+name+"="+(secretNames.contains(name)||name.toUpperCase(Locale.ROOT).matches(SENSITIVE_NAME)?"[REDACTED]":redact(value))));}
              static void report(Throwable error,boolean debug){
                StringJoiner messages=new StringJoiner("; caused by: ");
                Set<Throwable> seen=Collections.newSetFromMap(new IdentityHashMap<>());
                for(Throwable cause=error;cause!=null&&seen.size()<16&&seen.add(cause);cause=cause.getCause())messages.add(cause.getClass().getSimpleName()+": "+Objects.toString(cause.getMessage(),"No detail provided."));
                System.err.println("Pipeline failed: "+redact(messages.toString()));
                if(debug){StringWriter buffer=new StringWriter();error.printStackTrace(new PrintWriter(buffer));System.err.print(redact(buffer.toString()));}
                else System.err.println("Use --debug for a redacted stack trace, or --help for usage.");
              }
              static String required(Map<String,String> flags,String flag){String value=flags.get(flag);if(value==null||value.isBlank())throw new IllegalArgumentException("Missing "+flag);return value;}
              static File inputFile(String value){File file=new File(value);if(!file.isFile()||!file.canRead())throw new IllegalArgumentException("Input file does not exist or is not readable: "+value);return file;}
              static void configure(Plugin plugin,Map<String,Object> config,com.core.docintel.pipeline.events.RunEvents events){try{configure(plugin,config);}finally{secrets.forEach(events.sanitizer()::register);}}
              static void configure(Plugin plugin,Map<String,Object> config){if(plugin instanceof ConfigurablePlugin configurable)try{Map<String,Object> effective=new LinkedHashMap<>(config);String timeout=System.getenv("OLLAMA_TIMEOUT_SECONDS");if(effective.containsKey("OLLAMA_TIMEOUT_SECONDS")&&timeout!=null&&!timeout.isBlank()){try{long seconds=Long.parseLong(timeout);if(seconds<1)throw new NumberFormatException();effective.put("OLLAMA_TIMEOUT_SECONDS",seconds);}catch(NumberFormatException error){throw new IllegalArgumentException("OLLAMA_TIMEOUT_SECONDS must be a positive whole number of seconds.");}}configurable.configure(resolveMap(effective));}catch(RuntimeException error){throw new IllegalArgumentException("Configuration for plugin '"+plugin.manifest().id()+"' failed.",error);}}
              static Map<String,Object> resolveMap(Map<?,?> values){Map<String,Object> resolved=new LinkedHashMap<>();values.forEach((key,value)->resolved.put(String.valueOf(key),resolve(value)));return resolved;}
              static Object resolve(Object value){
                if(value instanceof String text&&text.startsWith("secret://")){
                  String prefix="secret://env/";
                  if(!text.startsWith(prefix)||!text.substring(prefix.length()).matches("[A-Za-z_][A-Za-z0-9_]*"))throw new IllegalArgumentException("Invalid secret reference.");
                  String variable=text.substring(prefix.length()),secret=System.getenv(variable);if(secret==null||secret.isEmpty())throw new IllegalArgumentException("Required secret environment variable '"+variable+"' is not set. Export it in this terminal before running the application.");register(secret);return secret;
                }
                if(value instanceof Map<?,?> map)return resolveMap(map);
                if(value instanceof List<?> list)return list.stream().map(RuntimeSupport::resolve).toList();
                return value;
              }
            }
            """;}
    private static void collectSecretVariables(Object value,Set<String> variables){
        if(value instanceof String text&&text.startsWith("secret://env/")){String name=text.substring("secret://env/".length());if(name.matches("[A-Za-z_][A-Za-z0-9_]*"))variables.add(name);}
        else if(value instanceof Map<?,?> map)map.values().forEach(child->collectSecretVariables(child,variables));
        else if(value instanceof Collection<?> collection)collection.forEach(child->collectSecretVariables(child,variables));
    }
    private static String javaValue(Object value){
        if(value==null)return "null";
        if(value instanceof Map<?,?> map)return "Map.ofEntries("+String.join(",",map.entrySet().stream().map(entry->"Map.entry("+javaValue(String.valueOf(entry.getKey()))+","+javaValue(entry.getValue())+")").toList())+")";
        if(value instanceof List<?> list)return "List.of("+String.join(",",list.stream().map(PipelineProjectGenerator::javaValue).toList())+")";
        if(value instanceof Boolean)return value.toString();
        if(value instanceof Integer||value instanceof Short||value instanceof Byte)return value.toString();
        if(value instanceof Long)return value+"L";
        if(value instanceof Float)return value+"f";
        if(value instanceof Double)return value+"d";
        if(value instanceof java.math.BigDecimal)return "new java.math.BigDecimal("+javaValue(value.toString())+")";
        if(value instanceof java.math.BigInteger)return "new java.math.BigInteger("+javaValue(value.toString())+")";
        try{return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(String.valueOf(value));}
        catch(IOException e){throw new IllegalArgumentException("Could not export configuration.",e);}
    }
    private static String readme(String name, PipelineRequest request, List<PluginInfo> plugins,PipelineDefinition definition) {
        String artifact = name.toLowerCase(Locale.ROOT) + "-1.0.0.jar";
        StringBuilder readme = new StringBuilder("# ").append(name)
                .append("\n\nGenerated runnable pipeline. Plugin classes are linked directly into the application; execution and validation happen in one application process. The selected plugin JARs are bundled in `lib/`.\n\n## Build\n\n`")
                .append("native-image".equalsIgnoreCase(request.outputFormat())?"mvn -Pnative clean package":"mvn clean package").append("`\n");
        readme.append(GeneratedLaunchFiles.instructions(name,definition,"native-image".equalsIgnoreCase(request.outputFormat())));
        if ("native-image".equalsIgnoreCase(request.outputFormat())) {
            readme.append("\nThe generated native executable is in `target/").append(name)
                    .append("` (Linux/macOS) or `target/").append(name).append(".exe` (Windows).\n");
        }
        readme.append("\n## Plugins\n\n");
        for (PluginInfo plugin : plugins) readme.append("- `").append(plugin.id()).append("`: ")
                .append(plugin.inputType()).append(" -> ").append(plugin.outputType()).append("\n");
        readme.append("\n## Configuration\n\nSet local environment values from `pipeline-env.bat` or `pipeline-env.ps1` (`call pipeline-env.bat` in Command Prompt, `. .\\pipeline-env.ps1` in PowerShell). `.env.example` lists the variables. Secret values are placeholders and are never included from the browser request.\n");
        readme.append("\nFor Git Bash, export each variable named by a `secret://env/NAME` reference in the same terminal that runs Java. For example:\n\n```bash\nread -rsp 'Neo4j password: ' NEO4J_PASSWORD; echo\nexport NEO4J_PASSWORD\n```\nThe `.bat` and `.ps1` scripts do not set variables in a parent Bash shell. Keep the input path on one line; Windows paths can use forward slashes. Missing variables and unreadable inputs are reported explicitly. `OLLAMA_TIMEOUT_SECONDS` overrides the timeout embedded in the generated Ollama plugin configuration (for example, `export OLLAMA_TIMEOUT_SECONDS=600`). Add `--debug` to print the environment and stack trace; values whose variable names look sensitive are redacted.\n");
        if ("native-image".equalsIgnoreCase(request.outputFormat())) {
            readme.append("\nFor GraalVM native image, build with `mvn -Pnative package`. This requires GraalVM Native Image and a supported native toolchain; plugins that use unsupported dynamic reflection may need reachability metadata.\n");
        }
        return readme.toString();
    }
    private static String envExample(List<PluginInfo> ps){Set<String> keys=new TreeSet<>();ps.forEach(p->p.configuration().forEach(c->keys.add(c.name())));StringBuilder b=new StringBuilder("# Copy values into your local environment; never commit secrets.\n");for(String key:keys){ConfigOption o=ps.stream().flatMap(p->p.configuration().stream()).filter(c->c.name().equals(key)).findFirst().orElseThrow();b.append(key).append('=').append(o.sensitive()?"":o.defaultValue()).append('\n');}return b.toString();}
    private static String envFile(List<PluginInfo> ps,Map<String,String> values,boolean powershell){Set<String> keys=new TreeSet<>();ps.forEach(p->p.configuration().forEach(c->keys.add(c.name())));StringBuilder b=new StringBuilder(powershell?"# Set local values before running.\n":"@echo off\r\nREM Set local values before running.\r\n");for(String key:keys){ConfigOption o=ps.stream().flatMap(p->p.configuration().stream()).filter(c->c.name().equals(key)).findFirst().orElseThrow();String supplied=values==null?null:values.get(key);String value=o.sensitive()?"CHANGE_ME":(supplied==null||supplied.isBlank()?o.defaultValue():supplied);if(value==null)value="";if(value.contains("\r")||value.contains("\n"))throw new IllegalArgumentException("Configuration value contains a newline: "+key);if(powershell)b.append("$env:").append(key).append(" = '").append(value.replace("'","''")).append("'\n");else b.append("set \"").append(key).append("=").append(value.replace("%","%%").replace("\"","")).append("\"\r\n");}return b.toString();}
    private static String safeName(String s){if(s==null||!s.matches("[A-Za-z][A-Za-z0-9_-]{0,63}"))throw new IllegalArgumentException("Project name must start with a letter and contain only letters, digits, '_' or '-'.");return s;}
    private static String xml(String s){return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&apos;");}
    private static void write(Path p,String s)throws IOException{Files.writeString(p,s);}
}
