package com.core.docintel.pipeline.builder;

import jakarta.inject.Singleton;
import io.micronaut.context.annotation.Value;
import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.io.ByteArrayOutputStream;

@Singleton
public final class ProjectBuildManager {
    private final PipelineControlService control;
    private final PipelineProjectGenerator generator;
    private final MavenProjectBuilder maven;
    private final ControlPlaneAuthService auth;
    private final String bindAddress;
    private final ExecutorService workers=Executors.newVirtualThreadPerTaskExecutor();
    private final ConcurrentMap<String,Job> jobs=new ConcurrentHashMap<>();

    public ProjectBuildManager(PipelineControlService control, PipelineProjectGenerator generator, MavenProjectBuilder maven,
                               ControlPlaneAuthService auth,@Value("${micronaut.server.host:127.0.0.1}") String bindAddress) {
        this.control=control; this.generator=generator; this.maven=maven;this.auth=auth;this.bindAddress=bindAddress;
    }

    public ProjectBuildSnapshot start(ProjectBuildRequest request) {
        if(request==null)throw new IllegalArgumentException("Build request is required.");
        if(!auth.enabled()&&!Set.of("127.0.0.1","localhost","::1").contains(bindAddress.toLowerCase(Locale.ROOT)))
            throw new IllegalArgumentException("Project builds can write to the server filesystem. Enable control-plane authentication or bind the service to loopback before using this feature.");
        String format=request.outputFormat()==null?"":request.outputFormat().toLowerCase(Locale.ROOT);
        if(!Set.of("jar","native-image").contains(format))throw new IllegalArgumentException("Choose Simple JAR or Graal native executable.");
        String projectName=request.projectName()==null?"":request.projectName().trim();
        if(!projectName.matches("[A-Za-z][A-Za-z0-9_-]{0,63}"))throw new IllegalArgumentException("Project name must start with a letter and contain only letters, digits, '_' or '-'.");
        if(request.destination()==null||request.destination().isBlank())throw new IllegalArgumentException("Project location is required.");
        Path destination=Path.of(request.destination().trim());
        if(!destination.isAbsolute())throw new IllegalArgumentException("Project location must be an absolute path on the backend machine.");
        destination=destination.normalize();
        if(destination.getParent()==null)throw new IllegalArgumentException("Choose a project folder below a drive or filesystem root.");
        try { PipelineProjectGenerator.validateDestination(destination); }
        catch(IOException e){throw new IllegalArgumentException("Could not inspect the project location.",e);}
        PipelineVersionRecord version=control.version(request.pipelineId(),request.version()).orElseThrow(()->new IllegalArgumentException("Published pipeline version was not found."));
        PipelineDefinition definition=control.executionDefinition(version.definition());
        GraphValidation validation=control.validate(version.definition(),true);
        if(!validation.valid())throw new IllegalArgumentException(String.join("; ",validation.errors()));
        for(PipelineType type:List.of(PipelineType.DOCUMENT_INGESTION,PipelineType.QUERY_PROCESSING))if(definition.hasWorkflow(type))orderedNodes(definition.workflow(type));
        String id=UUID.randomUUID().toString(); Job job=new Job(id,request.pipelineId(),request.version(),projectName,destination,format);
        jobs.put(id,job); workers.submit(()->run(job,definition)); return job.snapshot();
    }

    public Optional<ProjectBuildSnapshot> get(String id){return Optional.ofNullable(jobs.get(id)).map(Job::snapshot);}
    public Optional<Path> artifact(String id){Job job=jobs.get(id);return job==null||!job.status.equals("SUCCEEDED")?Optional.empty():Optional.ofNullable(job.artifact);}
    public Optional<byte[]> bundle(String id){Job job=jobs.get(id);if(job==null||!job.status.equals("SUCCEEDED")||job.project==null)return Optional.empty();try{return Optional.of(zipProject(job.project,job.name));}catch(IOException e){throw new IllegalStateException("Could not package the generated project: "+e.getMessage(),e);}}

    private void run(Job job,PipelineDefinition definition){
        try {
            job.update("PREPARING",5,"Preparing project files at "+job.destination);
            Path project=generator.createProject(definition,job.name,job.format,job.destination,job.pipelineId,job.version);
            job.project=project; job.update("BUILDING",10,"Starting Maven build: "+(job.format.equals("jar")?"runnable JAR":"Graal native executable"));
            Path artifact=maven.build(project,job.format,job.name,line->{job.log(line);if(line.contains("Compiling")||line.contains("compile"))job.progress(35);else if(line.contains("Building jar"))job.progress(75);else if(line.contains("native-image")||line.contains("native-maven-plugin"))job.progress(65);});
            job.artifact=artifact;Files.deleteIfExists(project.resolve("target/maven-build.log"));job.progress(100);job.update("SUCCEEDED",100,"Build completed successfully.");
        } catch(Exception e){job.fail(safeMessage(e));}
    }

    static PipelineRequest chainRequest(PipelineDefinition definition,String name,String format){
        List<PipelineNode> ordered=orderedNodes(definition);
        Map<String,String> config=new LinkedHashMap<>();List<String> pluginIds=new ArrayList<>();
        for(PipelineNode node:ordered){pluginIds.add(node.pluginId());flattenConfig(node.config(),config);}
        return new PipelineRequest(name,pluginIds,format,config);
    }

    static List<PipelineNode> orderedNodes(PipelineDefinition definition){
        List<PipelineNode> nodes=definition.nodes()==null?List.of():definition.nodes();
        if(nodes.isEmpty())throw new IllegalArgumentException("The published pipeline has no nodes.");
        Map<String,PipelineNode> byId=new LinkedHashMap<>();nodes.forEach(node->byId.put(node.id(),node));
        Map<String,String> next=new HashMap<>();Map<String,Integer> incoming=new HashMap<>();
        for(PipelineEdge edge:definition.edges()==null?List.<PipelineEdge>of():definition.edges()){
            if(next.put(edge.from(),edge.to())!=null)throw new IllegalArgumentException("Project export currently supports a single linear workflow. Node '"+edge.from()+"' has multiple outgoing connections.");
            if(incoming.merge(edge.to(),1,Integer::sum)>1)throw new IllegalArgumentException("Project export currently supports a single linear workflow. Node '"+edge.to()+"' has multiple incoming connections.");
        }
        List<PipelineNode> ordered=new ArrayList<>();List<PipelineNode> starts=nodes.stream().filter(node->!incoming.containsKey(node.id())).toList();
        if(starts.isEmpty())throw new IllegalArgumentException("Pipeline contains a cycle.");
        if(starts.size()!=1)throw new IllegalArgumentException("Project export currently supports one connected linear workflow.");
        Set<String> seen=new HashSet<>();PipelineNode cursor=starts.getFirst();
        while(cursor!=null){if(!seen.add(cursor.id()))throw new IllegalArgumentException("Pipeline contains a cycle.");ordered.add(cursor);String id=next.get(cursor.id());cursor=id==null?null:byId.get(id);}
        if(ordered.size()!=nodes.size())throw new IllegalArgumentException("Project export requires every node to be connected in one linear workflow.");
        return List.copyOf(ordered);
    }

    private static void flattenConfig(Map<String,Object> config,Map<String,String> target){
        if(config==null)return;
        config.forEach((key,value)->{
            if(key.equals("connectionRef")||value==null)return;
            if(value instanceof Map<?,?> nested){nested.forEach((child,childValue)->{if(childValue!=null&&!(childValue instanceof Map<?,?>))target.put(String.valueOf(child),String.valueOf(childValue));});}
            else if(!(value instanceof List<?>))target.put(key,String.valueOf(value));
        });
    }
    private static String safeMessage(Exception e){String message=e.getMessage();return message==null||message.isBlank()?e.getClass().getSimpleName():message;}
    static byte[] zipProject(Path project,String name)throws IOException{
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        try(ZipOutputStream zip=new ZipOutputStream(bytes);var paths=Files.walk(project)){
            for(Path path:paths.filter(Files::isRegularFile).sorted().toList()){
                zip.putNextEntry(new ZipEntry(name+"/"+project.relativize(path).toString().replace('\\','/')));
                Files.copy(path,zip);zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private static final class Job{
        final String id,pipelineId,name,format;final int version;final Path destination;final Instant created=Instant.now();
        final List<String> logs=new CopyOnWriteArrayList<>();volatile String status="QUEUED",error,artifactName;volatile int progress;volatile Instant updated=created;volatile Path project,artifact;
        final SecretRedactor redactor=new SecretRedactor();
        Job(String id,String pipelineId,int version,String name,Path destination,String format){this.id=id;this.pipelineId=pipelineId;this.version=version;this.name=name;this.destination=destination;this.format=format;
            System.getenv().forEach((key,value)->{if(key.toUpperCase(Locale.ROOT).matches(".*(PASSWORD|PASSWD|TOKEN|SECRET|KEY|CREDENTIAL).*"))redactor.register(value);});
        }
        synchronized void update(String state,int value,String line){status=state;progress=Math.max(progress,value);log(line);updated=Instant.now();}
        synchronized void progress(int value){progress=Math.max(progress,Math.min(99,value));updated=Instant.now();}
        synchronized void log(String line){if(line==null||line.isBlank())return;logs.add(redactor.redact(line));if(logs.size()>2000)logs.remove(0);updated=Instant.now();}
        synchronized void fail(String message){status="FAILED";error=redactor.redact(message);log(message);updated=Instant.now();}
        ProjectBuildSnapshot snapshot(){return new ProjectBuildSnapshot(id,pipelineId,version,name,destination.toString(),format,status,progress,List.copyOf(logs),error,artifact==null?null:artifact.getFileName().toString(),created,updated);}
    }
}
