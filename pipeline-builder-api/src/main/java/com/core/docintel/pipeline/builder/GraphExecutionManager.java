package com.core.docintel.pipeline.builder;

import com.core.docintel.pipeline.api.*;
import com.core.docintel.pipeline.core.PluginLoader;
import io.micronaut.context.annotation.Value;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Singleton;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** Executes a published single-root DAG; parallel stages require explicit plugin opt-in. */
@Singleton
public final class GraphExecutionManager {
    private final PipelineControlService control;
    private final PluginCatalog catalog;
    private final GraphPipelineValidator validator;
    private final ExecutionStore executionStore;
    private final Path inputRoot;
    private final NodeTaskRunner nodeRunner;
    private final int maxParallelism;
    private final ExecutorService workers=Executors.newVirtualThreadPerTaskExecutor();
    private final ConcurrentMap<String,Run> runs=new ConcurrentHashMap<>();

    @jakarta.inject.Inject
    public GraphExecutionManager(PipelineControlService control,PluginCatalog catalog,GraphPipelineValidator validator,ExecutionStore executionStore,
                                 NodeTaskRunner nodeRunner,@Value("${pipeline.execution.input-root:./data/ingestion}") String inputRoot,
                                 @Value("${pipeline.execution.max-parallelism:4}") int maxParallelism){
        if(maxParallelism<1||maxParallelism>64)throw new IllegalArgumentException("pipeline.execution.max-parallelism must be between 1 and 64.");
        this.control=control;this.catalog=catalog;this.validator=validator;this.executionStore=executionStore;this.nodeRunner=nodeRunner;this.inputRoot=Path.of(inputRoot).toAbsolutePath().normalize();this.maxParallelism=maxParallelism;
    }
    public GraphExecutionManager(PipelineControlService control,PluginCatalog catalog,GraphPipelineValidator validator,ExecutionStore executionStore,String inputRoot){this(control,catalog,validator,executionStore,new NodeTaskRunner(),inputRoot,4);}

    public ExecutionAccepted start(ExecutionRequest request){
        return start(request,null);
    }

    public ExecutionAccepted start(ExecutionRequest request,PipelineType expectedType){
        if(request==null||request.pipelineId()==null||request.pipelineId().isBlank())throw new IllegalArgumentException("pipelineId is required.");
        if(request.version()<1)throw new IllegalArgumentException("A published pipeline version is required.");
        PipelineVersionRecord version=control.version(request.pipelineId(),request.version()).orElseThrow(()->new IllegalArgumentException("Published pipeline version not found."));
        if(expectedType!=null&&request.workflow()!=null&&request.workflow()!=expectedType)throw new IllegalArgumentException("Workflow conflicts with this endpoint.");
        PipelineType workflow=expectedType!=null?expectedType:request.workflow()!=null?request.workflow():version.definition().isProject()?null:version.definition().type();
        PipelineDefinition selected=version.definition().workflow(workflow);
        PipelineDefinition executionDefinition=control.executionDefinition(selected);
        GraphValidation validation=validator.validate(executionDefinition,catalog.all(),true);
        if(!validation.valid())throw new IllegalArgumentException("Published pipeline is no longer compatible with installed plugins: "+String.join("; ",validation.errors()));
        Object input=resolveInput(executionDefinition,request);
        String id=UUID.randomUUID().toString(); Run run=new Run(new ExecutionSnapshot(id,request.pipelineId(),request.version(),ExecutionStatus.QUEUED,Instant.now(),null,null,Map.of(),null,workflow));
        runs.put(id,run);executionStore.save(run.snapshot);run.future=workers.submit(()->execute(run,executionDefinition,validation.executionOrder(),input));
        return new ExecutionAccepted(id,ExecutionStatus.QUEUED);
    }

    public Optional<ExecutionSnapshot> get(String id){return executionStore.get(id);}
    public List<ExecutionSnapshot> list(){return executionStore.list();}
    public List<ExecutionEvent> events(String id){if(executionStore.get(id).isEmpty())throw new IllegalArgumentException("Execution not found: "+id);return executionStore.events(id);}
    public List<ExecutionEvent> events(String id,long afterSequence){if(afterSequence<0)throw new IllegalArgumentException("afterSequence must be zero or greater.");if(executionStore.get(id).isEmpty())throw new IllegalArgumentException("Execution not found: "+id);return executionStore.events(id,afterSequence);}
    public boolean cancel(String id){Run run=runs.get(id);if(run==null)return false;synchronized(run){if(run.snapshot.status()==ExecutionStatus.QUEUED){update(run,copy(run.snapshot,ExecutionStatus.CANCELLED,null,Instant.now(),Map.of(),null));run.future.cancel(true);emit(run,null,"CANCELLED","Execution was cancelled before it started.",0);return true;}if(run.snapshot.status()==ExecutionStatus.RUNNING){update(run,copy(run.snapshot,ExecutionStatus.CANCELLING,run.snapshot.startedAt(),null,Map.of(),null));run.future.cancel(true);return true;}return false;}}

    private void execute(Run run,PipelineDefinition definition,List<String> order,Object initialInput){
        Instant started=Instant.now();Map<String,Plugin> instances=new LinkedHashMap<>();
        try(PluginLoader loader=new PluginLoader()){
            synchronized(run){if(run.snapshot.status()==ExecutionStatus.CANCELLING){update(run,copy(run.snapshot,ExecutionStatus.CANCELLED,null,Instant.now(),Map.of(),null));return;}update(run,copy(run.snapshot,ExecutionStatus.RUNNING,started,null,Map.of(),null));}
            Map<String,PipelineNode> nodes=new HashMap<>();definition.nodes().forEach(n->nodes.put(n.id(),n));
            Map<String,List<String>> incoming=new HashMap<>();definition.edges().forEach(e->incoming.computeIfAbsent(e.to(),k->new ArrayList<>()).add(e.from()));
            Map<String,Integer> ranks=new HashMap<>();for(int i=0;i<order.size();i++)ranks.put(order.get(i),i);
            incoming.values().forEach(parents->parents.sort(Comparator.comparingInt(ranks::get)));
            Map<String,PluginInfo> available=new HashMap<>();catalog.all().forEach(p->available.put(p.id(),p));
            PreflightContext context=new PreflightContext();
            for(String nodeId:order){checkCancelled();PipelineNode node=nodes.get(nodeId);PluginInfo info=available.get(node.pluginId());Plugin plugin=loader.load(catalog.pluginJar(info));
                if(info.capabilities().contains("parallel-safe")&&!(plugin instanceof ParallelSafePlugin))throw new IllegalArgumentException("Plugin '"+node.pluginId()+"' declares parallel-safe but does not implement ParallelSafePlugin.");
                if(node.config()!=null&&!node.config().isEmpty()){
                    if(!(plugin instanceof ConfigurablePlugin configurable))throw new IllegalArgumentException("Plugin '"+node.pluginId()+"' does not accept per-node configuration.");
                    configurable.configure(resolveSecrets(node.config(),run));
                }
                instances.put(nodeId,plugin);emit(run,nodeId,"READY","Plugin loaded and configuration validated.",0);
            }
            for(String nodeId:order){checkCancelled();Plugin plugin=instances.get(nodeId);if(plugin instanceof PreflightPlugin preflight){PipelineNode node=nodes.get(nodeId);emit(run,nodeId,"PREFLIGHT","Checking plugin prerequisites.",0);nodeRunner.execute(node.effectiveExecutionPolicy(),()->{preflight.preflight(context);return null;},(state,message,duration)->emit(run,nodeId,"PREFLIGHT_"+state,run.redact(message),duration));}}
            Map<String,Object> outputs=new ConcurrentHashMap<>();
            Map<Integer,List<String>> layers=layers(order,incoming);
            for(List<String> layer:layers.values()){
                checkCancelled();
                boolean parallel=layer.size()>1&&maxParallelism>1&&layer.stream().allMatch(id->instances.get(id) instanceof ParallelSafePlugin&&available.get(nodes.get(id).pluginId()).capabilities().contains("parallel-safe"));
                if(parallel)executeParallelLayer(run,layer,nodes,incoming,instances,outputs,initialInput);
                else for(String nodeId:layer){checkCancelled();outputs.put(nodeId,executeFromParents(run,nodeId,nodes,incoming,instances,outputs,initialInput));}
            }
            Map<String,Object> terminal=new LinkedHashMap<>();Set<String> hasChild=new HashSet<>();definition.edges().forEach(e->hasChild.add(e.from()));
            for(String nodeId:order)if(!hasChild.contains(nodeId))terminal.put(nodeId,safeOutput(outputs.get(nodeId)));
            synchronized(run){update(run,copy(run.snapshot,ExecutionStatus.SUCCEEDED,started,Instant.now(),terminal,null));}
        }catch(CancellationException|InterruptedException e){Thread.currentThread().interrupt();synchronized(run){update(run,copy(run.snapshot,ExecutionStatus.CANCELLED,started,Instant.now(),Map.of(),null));}emit(run,null,"CANCELLED","Execution was cancelled.",0);}
        catch(Throwable e){boolean cancelled;String message=run.redact(safeMessage(e));synchronized(run){cancelled=run.snapshot.status()==ExecutionStatus.CANCELLING;if(cancelled)update(run,copy(run.snapshot,ExecutionStatus.CANCELLED,started,Instant.now(),Map.of(),null));else update(run,copy(run.snapshot,ExecutionStatus.FAILED,started,Instant.now(),Map.of(),message));}emit(run,null,cancelled?"CANCELLED":"FAILED",cancelled?"Execution was cancelled.":message,0);}
        finally{List<Plugin> reverse=new ArrayList<>(instances.values());Collections.reverse(reverse);for(Plugin plugin:reverse)if(plugin instanceof ManagedPlugin managed)try{managed.close();}catch(RuntimeException ignored){}}
    }

    private Object resolveInput(PipelineDefinition definition,ExecutionRequest request){
        Map<String,PluginInfo> metadata=new HashMap<>();catalog.all().forEach(p->metadata.put(p.id(),p));
        PipelineNode root=definition.nodes().stream().filter(n->definition.edges().stream().noneMatch(e->e.to().equals(n.id()))).findFirst().orElseThrow(()->new IllegalArgumentException("Pipeline has no root node."));
        PluginInfo plugin=metadata.get(root.pluginId());if(plugin==null)throw new IllegalArgumentException("Root plugin is not installed.");
        if("SearchRequest".equals(plugin.inputType())){if(request.query()==null||request.query().isBlank())throw new IllegalArgumentException("query is required for this pipeline.");return new SearchRequest(request.query(),!Boolean.FALSE.equals(request.includeCitations()),request.topK()==null?5:request.topK());}
        if("File".equals(plugin.inputType())){
            if(request.filePath()==null||request.filePath().isBlank())throw new IllegalArgumentException("filePath is required for this pipeline.");
            try{Files.createDirectories(inputRoot);Path rootPath=inputRoot.toRealPath();Path given=Path.of(request.filePath());Path candidate=(given.isAbsolute()?given:rootPath.resolve(given)).normalize();if(!candidate.startsWith(rootPath))throw new IllegalArgumentException("Input file must be inside PIPELINE_INPUT_ROOT.");Path real=candidate.toRealPath();if(!real.startsWith(rootPath)||!Files.isRegularFile(real)||!Files.isReadable(real))throw new IllegalArgumentException("Input path must be a readable file inside PIPELINE_INPUT_ROOT.");return real.toFile();}
            catch(java.io.IOException e){throw new IllegalArgumentException("Could not resolve ingestion file: "+e.getMessage(),e);}
        }
        throw new IllegalArgumentException("REST execution currently supports File ingestion and SearchRequest query inputs, not '"+plugin.inputType()+"'.");
    }
    private Object executeNode(Run run,PipelineNode node,Plugin plugin,Object input)throws InterruptedException{
        return nodeRunner.execute(node.effectiveExecutionPolicy(),()->plugin.execute(input),(state,message,duration)->emit(run,node.id(),state,run.redact(message),duration));
    }
    private Object executeFromParents(Run run,String nodeId,Map<String,PipelineNode> nodes,Map<String,List<String>> incoming,Map<String,Plugin> instances,Map<String,Object> outputs,Object initialInput)throws InterruptedException{
        PipelineNode node=nodes.get(nodeId);List<String> parents=incoming.getOrDefault(nodeId,List.of());Plugin plugin=instances.get(nodeId);
        Object output;
        if(parents.size()>1){if(!(plugin instanceof FanInPlugin fanIn))throw new IllegalStateException("Node '"+nodeId+"' declares fan-in but its runtime plugin does not implement FanInPlugin.");output=executeFanInNode(run,node,fanIn,parents.stream().map(outputs::get).toList());}
        else{Object input=parents.isEmpty()?initialInput:outputs.get(parents.getFirst());output=executeNode(run,node,plugin,input);}
        if(output==null)throw new IllegalStateException("Node '"+nodeId+"' returned a null output.");
        return output;
    }
    private void executeParallelLayer(Run run,List<String> layer,Map<String,PipelineNode> nodes,Map<String,List<String>> incoming,Map<String,Plugin> instances,Map<String,Object> outputs,Object initialInput)throws InterruptedException{
        for(int start=0;start<layer.size();start+=maxParallelism){
            List<String> batch=layer.subList(start,Math.min(start+maxParallelism,layer.size()));
            if(batch.size()==1){String id=batch.getFirst();outputs.put(id,executeFromParents(run,id,nodes,incoming,instances,outputs,initialInput));continue;}
            List<Future<NodeOutput>> futures=new ArrayList<>();
            for(String id:batch)futures.add(workers.submit(()->new NodeOutput(id,executeFromParents(run,id,nodes,incoming,instances,outputs,initialInput))));
            try{for(Future<NodeOutput> future:futures){NodeOutput result=future.get();outputs.put(result.nodeId(),result.output());}}
            catch(ExecutionException failure){futures.forEach(future->future.cancel(true));Throwable cause=failure.getCause()==null?failure:failure.getCause();if(cause instanceof InterruptedException interrupted)throw interrupted;if(cause instanceof RuntimeException runtime)throw runtime;throw new IllegalStateException("Parallel pipeline node failed: "+safeMessage(cause),cause);}
            catch(InterruptedException interrupted){futures.forEach(future->future.cancel(true));throw interrupted;}
        }
    }
    private static Map<Integer,List<String>> layers(List<String> order,Map<String,List<String>> incoming){
        Map<String,Integer> depth=new HashMap<>();Map<Integer,List<String>> result=new TreeMap<>();
        for(String id:order){List<String> parents=incoming.getOrDefault(id,List.of());int level=parents.stream().mapToInt(parent->depth.getOrDefault(parent,0)).max().orElse(-1)+1;depth.put(id,level);result.computeIfAbsent(level,ignored->new ArrayList<>()).add(id);}
        return result;
    }
    private record NodeOutput(String nodeId,Object output){}
    private Object executeFanInNode(Run run,PipelineNode node,FanInPlugin plugin,List<Object> inputs)throws InterruptedException{
        return nodeRunner.execute(node.effectiveExecutionPolicy(),()->plugin.executeInputs(inputs),(state,message,duration)->emit(run,node.id(),state,run.redact(message),duration));
    }
    private static Map<String,Object> resolveSecrets(Map<String,Object> config,Run run){return SecretReferences.resolve(config,run.redactor);}
    private static Object safeOutput(Object output){if(output instanceof AnswerResult answer)return Map.of("query",answer.query(),"answer",answer.answer(),"citationsEnabled",answer.citationsEnabled(),"citationCount",answer.citations().size());if(output instanceof StoredResult stored)return Map.of("storedChunks",stored.storedChunks());if(output instanceof RetrievalResult result)return Map.of("query",result.query(),"retrievedCount",result.chunks().size());return Map.of("type",output==null?"null":output.getClass().getSimpleName());}
    private static String safeMessage(Throwable error){String value=error.getMessage();if(value==null||value.isBlank())return error.getClass().getSimpleName();return value.length()>500?value.substring(0,500):value;}
    private void emit(Run run,String node,String state,String message,long duration){ExecutionEvent event=new ExecutionEvent(run.sequence.incrementAndGet(),Instant.now(),node,state,message,duration);run.events.add(event);executionStore.append(run.snapshot.id(),event);}
    private void update(Run run,ExecutionSnapshot snapshot){run.snapshot=snapshot;executionStore.save(snapshot);}
    private static void checkCancelled() throws InterruptedException{if(Thread.currentThread().isInterrupted())throw new InterruptedException("cancelled");}
    private static ExecutionSnapshot copy(ExecutionSnapshot old,ExecutionStatus status,Instant started,Instant finished,Map<String,Object> output,String error){return new ExecutionSnapshot(old.id(),old.pipelineId(),old.pipelineVersion(),status,old.submittedAt(),started,finished,output,error,old.workflow());}
    @PreDestroy public void close(){workers.shutdownNow();nodeRunner.close();}
    private static final class Run{volatile ExecutionSnapshot snapshot;final List<ExecutionEvent> events=new CopyOnWriteArrayList<>();final SecretRedactor redactor=new SecretRedactor();final AtomicLong sequence=new AtomicLong();volatile Future<?> future;Run(ExecutionSnapshot value){snapshot=value;}String redact(String message){return redactor.redact(message);}}
}
