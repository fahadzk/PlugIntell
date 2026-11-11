package com.core.docintel.pipeline.builder;

import com.core.docintel.pipeline.api.*;
import com.core.docintel.pipeline.core.PluginLoader;
import com.core.docintel.pipeline.events.*;
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
    private final StudioHistoryRecorder history;
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
        this.control=control;this.catalog=catalog;this.validator=validator;this.executionStore=executionStore;this.history=new StudioHistoryRecorder(executionStore);this.nodeRunner=nodeRunner;this.inputRoot=Path.of(inputRoot).toAbsolutePath().normalize();this.maxParallelism=maxParallelism;
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
        PipelineDescriptor descriptor=descriptor(request.pipelineId(),request.version(),executionDefinition);
        run.definition=executionDefinition;run.view=CapabilitySettings.view(executionDefinition.capabilities(),workflow);run.state=new WorkflowStateStore(id,descriptor,5000);
        run.historySnapshot=()->historySnapshot(run);
        StudioHistoryRecorder.RunDestination persistence=history.forRun(id,run.historySnapshot);
        run.telemetry=new RunEvents(id,request.pipelineId(),request.version(),run.dispatcher,event->{run.state.accept(event);persistence.accept(event);});
        new AdapterService().wire(executionDefinition.capabilities(),workflow,run.dispatcher,descriptor,run.state);
        synchronized(runs){if(runs.size()>=100)runs.entrySet().removeIf(e->Set.of(ExecutionStatus.SUCCEEDED,ExecutionStatus.FAILED,ExecutionStatus.CANCELLED).contains(e.getValue().snapshot.status())&&e.getValue().activeTasks.get()==0);if(runs.size()>=100){run.dispatcher.close();throw new IllegalArgumentException("Concurrent execution limit reached.");}runs.put(id,run);}
        executionStore.save(run.snapshot);run.future=workers.submit(()->execute(run,executionDefinition,validation.executionOrder(),input));
        return new ExecutionAccepted(id,ExecutionStatus.QUEUED);
    }

    public Optional<ExecutionSnapshot> get(String id){return executionStore.get(id);}
    public List<ExecutionSnapshot> list(){return executionStore.list();}
    public List<ExecutionEvent> events(String id){return events(id,0);}
    public List<ExecutionEvent> events(String id,long afterSequence){
        if(afterSequence<0)throw new IllegalArgumentException("afterSequence must be zero or greater.");
        if(executionStore.get(id).isEmpty())throw new IllegalArgumentException("Execution not found: "+id);
        Map<Long,ExecutionEvent> merged=new TreeMap<>();executionStore.events(id,afterSequence).forEach(event->merged.put(event.sequence(),event));
        Run run=runs.get(id);if(run!=null)synchronized(run.legacyEvents){run.legacyEvents.stream().filter(event->event.sequence()>afterSequence).forEach(event->merged.put(event.sequence(),event));}
        return List.copyOf(merged.values());
    }
    public boolean cancel(String id){Run run=runs.get(id);if(run==null)return false;synchronized(run){if(run.snapshot.status()==ExecutionStatus.QUEUED){update(run,copy(run.snapshot,ExecutionStatus.CANCELLING,null,null,Map.of(),null));update(run,copy(run.snapshot,ExecutionStatus.CANCELLED,null,Instant.now(),Map.of(),null));if(run.future!=null)run.future.cancel(true);emit(run,null,"CANCELLED","Execution was cancelled before it started.",0);return true;}if(run.snapshot.status()==ExecutionStatus.RUNNING){update(run,copy(run.snapshot,ExecutionStatus.CANCELLING,run.snapshot.startedAt(),null,Map.of(),null));if(run.future!=null)run.future.cancel(true);return true;}return false;}}

    private void execute(Run run,PipelineDefinition definition,List<String> order,Object initialInput){
        Instant started=Instant.now();Map<String,Plugin> instances=new LinkedHashMap<>();
        PluginLoader loader=new PluginLoader();run.loader=loader;
        try{
            synchronized(run){if(run.snapshot.status()==ExecutionStatus.CANCELLED)return;if(run.snapshot.status()==ExecutionStatus.CANCELLING){update(run,copy(run.snapshot,ExecutionStatus.CANCELLED,null,Instant.now(),Map.of(),null));return;}update(run,copy(run.snapshot,ExecutionStatus.RUNNING,started,null,Map.of(),null));}
            Map<String,PipelineNode> nodes=new HashMap<>();definition.nodes().forEach(n->nodes.put(n.id(),n));
            Map<String,List<String>> incoming=new HashMap<>();definition.edges().forEach(e->incoming.computeIfAbsent(e.to(),k->new ArrayList<>()).add(e.from()));
            Map<String,Integer> ranks=new HashMap<>();for(int i=0;i<order.size();i++)ranks.put(order.get(i),i);
            incoming.values().forEach(parents->parents.sort(Comparator.comparingInt(ranks::get)));
            Map<String,PluginInfo> available=new HashMap<>();catalog.all().forEach(p->available.put(p.id(),p));
            PreflightContext context=new PreflightContext();run.preflight=context;
            for(String nodeId:order){checkCancelled();PipelineNode node=nodes.get(nodeId);PluginInfo info=available.get(node.pluginId());RunEvents.Attempt scope=run.telemetry.prepareAttempt(nodeId,node.pluginId(),1);run.scopes.computeIfAbsent(nodeId,k->new ConcurrentHashMap<>()).put(1,scope);Plugin plugin=loader.load(catalog.pluginJar(info),scope);
                if(info.capabilities().contains("parallel-safe")&&!(plugin instanceof ParallelSafePlugin))throw new IllegalArgumentException("Plugin '"+node.pluginId()+"' declares parallel-safe but does not implement ParallelSafePlugin.");
                if(node.config()!=null&&!node.config().isEmpty()){
                    if(!(plugin instanceof ConfigurablePlugin configurable))throw new IllegalArgumentException("Plugin '"+node.pluginId()+"' does not accept per-node configuration.");
                    configurable.configure(resolveSecrets(node.config(),run));
                }
                instances.put(nodeId,plugin);emit(run,nodeId,"READY","Plugin loaded and configuration validated.",0);
            }
            for(String nodeId:order){checkCancelled();Plugin plugin=instances.get(nodeId);if(plugin instanceof PreflightPlugin preflight){PipelineNode node=nodes.get(nodeId);emit(run,nodeId,"PREFLIGHT","Checking plugin prerequisites.",0);nodeRunner.execute(node.effectiveExecutionPolicy(),()->{preflight.preflight(context);return null;},new NodeTaskRunner.Listener(){public void event(String state,String message,long duration){}public void attemptEvent(int attempt,String state,String message,long duration){if(state.equals("RUNNING"))run.activeTasks.incrementAndGet();run.telemetry.event(nodeId,node.pluginId(),"PREFLIGHT_"+state,run.redact(message),Map.of("phase","preflight","preflightAttempt",attempt),duration);emit(run,nodeId,"PREFLIGHT_"+state,run.redact(message),duration);if(state.equals("TERMINATED"))taskStopped(run);}});}}
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
        }catch(CancellationException|InterruptedException e){Thread.currentThread().interrupt();synchronized(run){if(run.activeTasks.get()==0)update(run,copy(run.snapshot,ExecutionStatus.CANCELLED,started,Instant.now(),Map.of(),null));}emit(run,null,"CANCELLED","Execution was cancelled.",0);}
        catch(Throwable e){boolean cancelled;String message=run.redact(safeMessage(e));synchronized(run){cancelled=run.snapshot.status()==ExecutionStatus.CANCELLING;if(cancelled){if(run.activeTasks.get()==0)update(run,copy(run.snapshot,ExecutionStatus.CANCELLED,started,Instant.now(),Map.of(),null));}else update(run,copy(run.snapshot,ExecutionStatus.FAILED,started,Instant.now(),Map.of(),message));}emit(run,null,cancelled?"CANCELLED":"FAILED",cancelled?"Execution was cancelled.":message,0);}
        finally{run.scopes.values().forEach(map->map.values().forEach(RunEvents.Attempt::close));run.cleanup=()->{List<Plugin> reverse=new ArrayList<>(instances.values());Collections.reverse(reverse);for(Plugin plugin:reverse)if(plugin instanceof ManagedPlugin managed)try{managed.close();}catch(RuntimeException ignored){}loader.close();run.dispatcher.close();};if(run.activeTasks.get()==0)cleanup(run);}
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
        return executeAttempt(run,node,plugin,DataSummaries.of(input),p->p.execute(input));
    }
    private Object executeFromParents(Run run,String nodeId,Map<String,PipelineNode> nodes,Map<String,List<String>> incoming,Map<String,Plugin> instances,Map<String,Object> outputs,Object initialInput)throws InterruptedException{
        PipelineNode node=nodes.get(nodeId);List<String> parents=incoming.getOrDefault(nodeId,List.of());Plugin plugin=instances.get(nodeId);
        Object output;
        for(String parent:parents)run.telemetry.event(parent,nodes.get(parent).pluginId(),"HANDOFF","Output passed to downstream node",Map.of("fromNodeId",parent,"toNodeId",nodeId),null);
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
        return executeAttempt(run,node,plugin,DataSummaries.of(inputs),p->((FanInPlugin)p).executeInputs(inputs));
    }
    private Object executeAttempt(Run run,PipelineNode node,Plugin original,Map<String,Object> inputSummary,java.util.function.Function<Plugin,Object> operation)throws InterruptedException{
        long nodeStarted=System.nanoTime();java.util.concurrent.atomic.AtomicReference<Map<String,Object>> outputSummary=new java.util.concurrent.atomic.AtomicReference<>(Map.of());
        run.telemetry.event(node.id(),node.pluginId(),"NODE_STARTED","Node started",Map.of("inputSummary",inputSummary),null);
        return nodeRunner.executeAttempts(node.effectiveExecutionPolicy(),number->()->{
            RunEvents.Attempt scope=run.scopes.get(node.id()).get(number);
            Plugin plugin=number==1?original:run.loader.reportingAttempt(original,scope);
            try{if(plugin!=original){if(plugin instanceof ConfigurablePlugin configurable)configurable.configure(resolveSecrets(node.config()==null?Map.of():node.config(),run));if(plugin instanceof PreflightPlugin preflight)preflight.preflight(run.preflight);}Object result=operation.apply(plugin);if(result==null)throw new IllegalStateException("Node '"+node.id()+"' returned a null output.");outputSummary.set(DataSummaries.of(result));return result;}
            finally{scope.close();if(plugin!=original&&plugin instanceof ManagedPlugin managed)managed.close();}
        },new NodeTaskRunner.Listener(){
            public void event(String state,String message,long duration){}
            public void attemptEvent(int number,String state,String message,long duration){
                var scopes=run.scopes.computeIfAbsent(node.id(),k->new ConcurrentHashMap<>());
                RunEvents.Attempt scope=scopes.computeIfAbsent(number,n->run.telemetry.prepareAttempt(node.id(),node.pluginId(),n));
                String safe=run.redact(message);
                switch(state){
                    case "RUNNING"->{run.activeTasks.incrementAndGet();scope.start();}
                    case "SUCCEEDED"->{scope.finish("ATTEMPT_SUCCEEDED",safe,duration);run.telemetry.event(node.id(),node.pluginId(),"NODE_SUCCEEDED",safe,Map.of("outputSummary",outputSummary.get()),TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-nodeStarted));}
                    case "ATTEMPT_FAILED"->scope.finish("ATTEMPT_FAILED",safe,duration);
                    case "TIMEOUT"->scope.finish("ATTEMPT_TIMEOUT",safe,duration);
                    case "RETRYING"->run.telemetry.event(node.id(),node.pluginId(),"NODE_RETRYING",safe,Map.of(),duration);
                    case "FAILED"->{scope.close();run.telemetry.event(node.id(),node.pluginId(),"NODE_FAILED",safe,Map.of(),TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-nodeStarted));}
                    case "CANCELLATION_REQUESTED"->{scope.close();run.cancelRequested.add(scope.id());scope.lifecycle("CANCELLATION_REQUESTED",safe,duration);}
                    case "TERMINATED"->{scope.lifecycle("ATTEMPT_TERMINATED",safe,duration);if(run.cancelRequested.remove(scope.id()))scope.lifecycle("NODE_CANCELLED","Node stopped after cancellation",duration);taskStopped(run);}
                    default->{}
                }
                if(!Set.of("TERMINATED","TIMEOUT","ATTEMPT_FAILED","CANCELLATION_REQUESTED").contains(state))emit(run,node.id(),state,safe,duration);
            }
        });
    }
    private void taskStopped(Run run){if(run.activeTasks.decrementAndGet()==0){synchronized(run){if(run.snapshot.status()==ExecutionStatus.CANCELLING)update(run,copy(run.snapshot,ExecutionStatus.CANCELLED,run.snapshot.startedAt(),Instant.now(),Map.of(),null));}if(run.cleanup!=null)cleanup(run);}}
    private static void cleanup(Run run){Runnable action;synchronized(run){action=run.cleanup;run.cleanup=null;}if(action!=null)action.run();}
    private static Map<String,Object> resolveSecrets(Map<String,Object> config,Run run){registerSecrets(config,run);return SecretReferences.resolve(config,run.redactor);}
    private static void registerSecrets(Object value,Run run){if(value instanceof String text&&text.startsWith("secret://env/")){String secret=System.getenv(text.substring("secret://env/".length()));run.telemetry.sanitizer().register(secret);}else if(value instanceof Map<?,?> m)m.values().forEach(v->registerSecrets(v,run));else if(value instanceof List<?> l)l.forEach(v->registerSecrets(v,run));}
    private PipelineDescriptor descriptor(String id,int version,PipelineDefinition d){Map<String,PluginInfo> metadata=new HashMap<>();catalog.all().forEach(p->metadata.put(p.id(),p));return new PipelineDescriptor(id,version,d.type().name(),d.nodes().stream().map(n->{PluginInfo p=metadata.get(n.pluginId());return new PipelineDescriptor.Node(n.id(),n.pluginId(),p==null?n.pluginId():p.name(),p==null?List.of():p.capabilities());}).toList(),d.edges().stream().map(e->new PipelineDescriptor.Edge(e.from(),e.to())).toList());}
    public Map<String,Object> monitor(String id){
        Run run=runs.get(id);
        if(run!=null){Map<String,Object> m=new LinkedHashMap<>(run.state.snapshot());List<Map<String,Object>> health=new ArrayList<>(run.dispatcher.health());health.add(history.health());m.put("health",health);m.put("template",CapabilitySettings.map(run.definition.capabilities()==null?null:run.definition.capabilities().get("template")));m.putAll(run.telemetry.diagnostics());return run.view.snapshot(m,run.state.after(0).stream().map(com.core.docintel.pipeline.events.ExecutionEvent::toMap).toList());}
        Map<String,Object> persisted=new LinkedHashMap<>(executionStore.monitor(id).orElseGet(()->legacyMonitor(id)));
        ExecutionSnapshot job=get(id).orElseThrow(()->new IllegalArgumentException("Execution not found."));
        if(!persisted.containsKey("template")){PipelineDefinition d=control.version(job.pipelineId(),job.pipelineVersion()).orElseThrow().definition();persisted.put("template",CapabilitySettings.map(d.capabilities()==null?null:d.capabilities().get("template")));}
        if(Set.of(ExecutionStatus.FAILED,ExecutionStatus.CANCELLED,ExecutionStatus.SUCCEEDED).contains(job.status())&&!job.status().name().equals(persisted.get("status"))){persisted.put("status",job.status().name());persisted.put("recoveredAfterRestart",true);}
        return savedView(persisted).snapshot(persisted,executionStore.runtimeEvents(id,0));
    }
    private EventView savedView(Map<String,Object> snapshot){Map<String,Object> descriptor=CapabilitySettings.map(snapshot.get("descriptor"));PipelineType workflow;try{workflow=PipelineType.valueOf(Objects.toString(descriptor.get("workflow"),""));}catch(Exception e){workflow=PipelineType.QUERY_PROCESSING;}return CapabilitySettings.view(CapabilitySettings.map(snapshot.get("capabilities")),workflow);}
    private Map<String,Object> legacyMonitor(String id){ExecutionSnapshot snapshot=get(id).orElseThrow(()->new IllegalArgumentException("Execution not found."));PipelineDefinition d=control.version(snapshot.pipelineId(),snapshot.pipelineVersion()).orElseThrow().definition();if(d.isProject())d=d.workflow(snapshot.workflow());WorkflowStateStore state=new WorkflowStateStore(id,descriptor(snapshot.pipelineId(),snapshot.pipelineVersion(),d),5000);Map<String,Object> m=new LinkedHashMap<>(state.snapshot());m.put("status",snapshot.status().name());m.put("legacy",true);m.put("health",List.of());return m;}
    public List<Map<String,Object>> runtimeEvents(String id,long after){if(after<0)throw new IllegalArgumentException("afterSequence must be nonnegative.");Run run=runs.get(id);if(run!=null)return run.state.after(after).stream().map(e->run.view.event(e.toMap())).toList();EventView view=savedView(executionStore.monitor(id).orElseGet(()->legacyMonitor(id)));return executionStore.runtimeEvents(id,after).stream().map(view::event).toList();}
    public boolean historyGap(String id,long cursor){Run run=runs.get(id);if(run!=null)return run.state.gap(cursor);Map<String,Object> snapshot=monitor(id);long sequence=((Number)snapshot.get("sequence")).longValue();if(cursor>sequence||cursor<Math.max(0,sequence-5000))return true;if(cursor==sequence)return false;List<Map<String,Object>> next=executionStore.runtimeEvents(id,cursor);return next.isEmpty()||((Number)next.getFirst().get("sequence")).longValue()>cursor+1;}

    private static Object safeOutput(Object output){if(output instanceof KnowledgeGraph.Result graph)return Map.of("type","KnowledgeGraphResult","processedChunks",graph.processedChunkIds().size(),"failedChunks",graph.failures().size(),"entitiesObserved",graph.writes().stream().mapToInt(KnowledgeGraph.WriteStatistics::entitiesObserved).sum(),"factsObserved",graph.writes().stream().mapToInt(KnowledgeGraph.WriteStatistics::factsObserved).sum(),"entitiesCreated",graph.writes().stream().mapToLong(KnowledgeGraph.WriteStatistics::entitiesCreated).sum(),"factsCreated",graph.writes().stream().mapToLong(KnowledgeGraph.WriteStatistics::factsCreated).sum(),"warnings",graph.warnings(),"failures",graph.failures().stream().map(f->Map.of("chunkId",f.chunkId(),"code",f.code())).toList(),"provenance",graph.provenance());if(output instanceof RetrievalResult graph&&!graph.graph().isEmpty())return Map.of("type","GraphRetrievalResult","retrievedCount",graph.chunks().size(),"strategy",Objects.toString(graph.graph().get("strategy"),"unknown"),"diagnostics",graph.graph().getOrDefault("diagnostics",Map.of()));if(output instanceof AnswerResult answer)return Map.of("query",answer.query(),"answer",answer.answer(),"citationsEnabled",answer.citationsEnabled(),"citationCount",answer.citations().size());if(output instanceof StoredResult stored)return Map.of("storedChunks",stored.storedChunks());if(output instanceof RetrievalResult result)return Map.of("query",result.query(),"retrievedCount",result.chunks().size());return Map.of("type",output==null?"null":output.getClass().getSimpleName());}
    private static String safeMessage(Throwable error){String value=error.getMessage();if(value==null||value.isBlank())return error.getClass().getSimpleName();return value.length()>500?value.substring(0,500):value;}
    private void emit(Run run,String node,String state,String message,long duration){ExecutionEvent event=new ExecutionEvent(run.sequence.incrementAndGet(),Instant.now(),node,state,message,duration);synchronized(run.legacyEvents){run.legacyEvents.addLast(event);while(run.legacyEvents.size()>5000)run.legacyEvents.removeFirst();}history.legacy(run.snapshot.id(),run.historySnapshot,event);}
    private static Map<String,Object> historySnapshot(Run run){Map<String,Object> snapshot=new LinkedHashMap<>(run.state.snapshot());snapshot.put("capabilities",run.definition.capabilities()==null?Map.of():run.definition.capabilities());snapshot.put("template",CapabilitySettings.map(run.definition.capabilities()==null?null:run.definition.capabilities().get("template")));return snapshot;}
    private void update(Run run,ExecutionSnapshot snapshot){ExecutionStatus old=run.snapshot.status();run.snapshot=snapshot;executionStore.save(snapshot);if(old!=snapshot.status()&&run.telemetry!=null){String type=switch(snapshot.status()){case RUNNING->"RUN_STARTED";case SUCCEEDED->"RUN_SUCCEEDED";case FAILED->"RUN_FAILED";case CANCELLING->"CANCELLATION_REQUESTED";case CANCELLED->"RUN_CANCELLED";default->null;};if(type!=null)run.telemetry.event(null,null,type,snapshot.error()==null?type:snapshot.error(),Map.of(),null);}}
    private static void checkCancelled() throws InterruptedException{if(Thread.currentThread().isInterrupted())throw new InterruptedException("cancelled");}
    private static ExecutionSnapshot copy(ExecutionSnapshot old,ExecutionStatus status,Instant started,Instant finished,Map<String,Object> output,String error){return new ExecutionSnapshot(old.id(),old.pipelineId(),old.pipelineVersion(),status,old.submittedAt(),started,finished,output,error,old.workflow());}
    @PreDestroy public void close(){runs.forEach((id,run)->cancel(id));workers.shutdownNow();nodeRunner.close();try{workers.awaitTermination(2,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}history.close();}
    private static final class Run{java.util.function.Supplier<Map<String,Object>> historySnapshot;final ArrayDeque<ExecutionEvent> legacyEvents=new ArrayDeque<>();PipelineDefinition definition;PluginLoader loader;PreflightContext preflight;WorkflowStateStore state;EventView view;RunEvents telemetry;final EventDispatcher dispatcher=new EventDispatcher();final ConcurrentMap<String,ConcurrentMap<Integer,RunEvents.Attempt>> scopes=new ConcurrentHashMap<>();final java.util.concurrent.atomic.AtomicInteger activeTasks=new java.util.concurrent.atomic.AtomicInteger();final Set<String> cancelRequested=ConcurrentHashMap.newKeySet();volatile Runnable cleanup;volatile ExecutionSnapshot snapshot;final SecretRedactor redactor=new SecretRedactor();final AtomicLong sequence=new AtomicLong();volatile Future<?> future;Run(ExecutionSnapshot value){snapshot=value;}String redact(String message){return redactor.redact(message);}}
}
