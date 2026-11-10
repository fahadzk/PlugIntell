package com.core.docintel.pipeline.events;
import com.core.docintel.pipeline.api.*;
import com.core.docintel.pipeline.core.Pipeline;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.io.*;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;
class ReportingTest {
 static final PipelineDescriptor DESCRIPTOR=new PipelineDescriptor("p",7,"QUERY_PROCESSING",List.of(new PipelineDescriptor.Node("a","fixture","A",List.of()),new PipelineDescriptor.Node("b","fixture","B",List.of())),List.of(new PipelineDescriptor.Edge("a","b")));
 static final class Fixture extends AbstractPlugin{
  Fixture(){super();}Fixture(PluginMessages messages){super(messages);}
  public PluginManifest manifest(){return new PluginManifest("fixture","Fixture","1.0.0","1.0",getClass().getName(),"String","String");}
  public Object execute(Object input){emit("embedding.started",Map.of("chunkCount",2));progress(1,(Long)null,"chunks");info("working");warning("retry may be needed");return input;}
 }
 @Test void noOpAndInheritedHelpersPreserveExecuteAndUnknownTotal(){
  assertEquals("x",new Fixture().execute("x"));List<PluginMessage> messages=new ArrayList<>();assertEquals("x",new Fixture(messages::add).execute("x"));assertEquals(4,messages.size());assertNull(messages.get(1).payload().get("total"));assertEquals("WARN",messages.getLast().severity());
  PluginMessages composed=messages::add;composed.emit("stage",Map.of("count",1));assertEquals(5,messages.size());
 }
 @Test void identitiesSequencesAndLateAttemptMessagesAreScopedAcrossThreads()throws Exception{
  List<ExecutionEvent> events=new ArrayList<>();RunEvents run=new RunEvents("r","p",7,null,events::add);
  RunEvents.Attempt first=run.attempt("a","fixture",1);first.progress(1,2L,"chunks");first.finish("ATTEMPT_FAILED","failed",4);
  RunEvents.Attempt second=run.attempt("a","fixture",2);
  try(ExecutorService workers=Executors.newVirtualThreadPerTaskExecutor()){for(int i=0;i<50;i++)workers.submit(()->second.emit("stage",Map.of("count",1)));}
  first.progress(2,2L,"chunks");assertEquals(1,run.lateEvents());
  assertEquals(54,events.size());for(int i=0;i<events.size();i++)assertEquals(i+1,events.get(i).sequence());
  assertTrue(events.stream().allMatch(e->"r".equals(e.runId())&&e.pipelineVersion()==7));
  assertNotEquals(first.id(),second.id());assertEquals(events.size(),events.stream().map(ExecutionEvent::eventId).distinct().count());
  List<ExecutionEvent> other=new ArrayList<>();new RunEvents("other","p",7,null,other::add).attempt("a","fixture",1);assertEquals(1,other.getFirst().sequence());assertNotEquals(other.getFirst().runId(),events.getFirst().runId());
 }
 @Test void runtimeReportsLifecycleForUninstrumentedPluginsAndRealHandoffs(){
  List<ExecutionEvent> events=new ArrayList<>();RunEvents run=new RunEvents("r",null,null,null,events::add);
  try(Pipeline pipeline=new Pipeline(List.of(new Fixture(),new Fixture()),run,List.of("a","b"))){assertEquals("x",pipeline.execute("x"));}
  assertEquals(2,events.stream().filter(e->e.type().equals("ATTEMPT_STARTED")).count());
  assertEquals(2,events.stream().filter(e->e.type().equals("NODE_SUCCEEDED")).count());
  assertEquals(1,events.stream().filter(e->e.type().equals("HANDOFF")).count());
 }
 @Test void routingDeduplicatesOverlapsAndDeliveryFailuresStayIsolated()throws Exception{
  AtomicInteger count=new AtomicInteger();CountDownLatch delivered=new CountDownLatch(1);
  try(EventDispatcher dispatcher=new EventDispatcher(8,Duration.ofMillis(100))){
   dispatcher.attach("shared",e->{count.incrementAndGet();delivered.countDown();},List.of(EventFilter.all(),new EventFilter(Set.of("a"),null,null)));
   dispatcher.attach("broken",e->{throw new IOException("offline");},List.of(EventFilter.all()));
   RunEvents run=new RunEvents("r",null,null,dispatcher,e->{});run.attempt("a","fixture",1);
   assertTrue(delivered.await(2,TimeUnit.SECONDS));assertEquals(1,count.get());
   for(int i=0;i<100&&((Number)dispatcher.health().get(1).get("failed")).intValue()==0;i++)Thread.sleep(5);
   assertEquals(1,((Number)dispatcher.health().get(1).get("failed")).intValue());
  }
 }
 @Test void overflowCoalescesProgressPrioritizesTerminalsAndShutdownIsBounded()throws Exception{
  CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
  EventDispatcher dispatcher=new EventDispatcher(2,Duration.ofMillis(50));
  dispatcher.attach("slow",e->{entered.countDown();release.await();},List.of(EventFilter.all()));
  RunEvents run=new RunEvents("r",null,null,dispatcher,e->{});var attempt=run.attempt("a","fixture",1);assertTrue(entered.await(2,TimeUnit.SECONDS));
  attempt.progress(1,10L,"chunks");attempt.progress(2,10L,"chunks");attempt.emit("detail",Map.of());attempt.emit("overflow",Map.of());attempt.finish("ATTEMPT_SUCCEEDED","done",1);
  var health=dispatcher.health().getFirst();assertTrue(((Number)health.get("coalesced")).longValue()>=1);assertTrue(((Number)health.get("dropped")).longValue()>=1);
  long start=System.nanoTime();dispatcher.close();assertTrue(Duration.ofNanos(System.nanoTime()-start).toMillis()<500);release.countDown();
 }
 @Test void shutdownWaitsForDeliveryFlushAndReportsCallbackFailuresWithoutPrivateHealth()throws Exception{
  CountDownLatch accepted=new CountDownLatch(1);AtomicBoolean flushed=new AtomicBoolean();
  EventDispatcher dispatcher=new EventDispatcher(8,Duration.ofSeconds(1));
  dispatcher.attach("publisher",new EventAdapter(){
   public void accept(ExecutionEvent event){accepted.countDown();}
   public void close(Duration timeout)throws Exception{Thread.sleep(80);flushed.set(true);}
   public Map<String,Object> health(){return Map.of("failed",flushed.get()?1:0,"privateCredential","do-not-print");}
  },List.of(EventFilter.all()));
  new RunEvents("r",null,null,dispatcher,e->{}).attempt("a","fixture",1);assertTrue(accepted.await(2,TimeUnit.SECONDS));
  dispatcher.close();assertTrue(flushed.get(),"CLI shutdown must wait for adapter flush within its budget");
  ByteArrayOutputStream bytes=new ByteArrayOutputStream();EventDiagnostics.report(dispatcher.health(),false,new PrintStream(bytes));
  String diagnostics=bytes.toString(java.nio.charset.StandardCharsets.UTF_8);assertTrue(diagnostics.contains("destinationFailed=1"));assertFalse(diagnostics.contains("do-not-print"));
 }
 @Test void reducerRejectsOldAttemptsAndResetsProgressRatherThanInventingMonotonicPercent(){
  WorkflowStateStore state=new WorkflowStateStore("r",DESCRIPTOR,3);List<ExecutionEvent> captured=new ArrayList<>();RunEvents run=new RunEvents("r","p",7,null,e->{captured.add(e);state.accept(e);});
  run.event(null,null,"RUN_STARTED","started",Map.of(),null);var first=run.attempt("a","fixture",1);first.progress(8,10L,"chunks");first.finish("ATTEMPT_FAILED","failed",1);run.event("a","fixture","NODE_RETRYING","retry",Map.of(),null);
  var next=run.attempt("a","fixture",2);next.progress(1,(Long)null,"chunks");first.lifecycle("ATTEMPT_TERMINATED","late stop",10);
  Map<?,?> node=(Map<?,?>)((Map<?,?>)state.snapshot().get("nodes")).get("a");assertEquals(2,node.get("attempt"));assertEquals("RUNNING",node.get("status"));assertEquals(1L,((Map<?,?>)node.get("progress")).get("completed"));assertNull(((Map<?,?>)node.get("progress")).get("total"));assertTrue(state.gap(1));assertEquals(3,state.after(0).size());
 }
 @Test void payloadAndMessagesAreRedactedWithoutSerializingProviderObjects(){
  List<ExecutionEvent> events=new ArrayList<>();RunEvents run=new RunEvents("r",null,null,null,events::add);run.sanitizer().register("sample-private-value");
  var attempt=run.attempt("a","fixture",1);attempt.report(new PluginMessage("stage","INFO","sample-private-value",Map.of("prompt","hidden text","count",2)));
  String json=EventJson.write(events.getLast().toMap());assertFalse(json.contains("sample-private-value"));assertFalse(json.contains("hidden text"));assertTrue(json.contains("[REDACTED]"));
 }
 @Test void blockedHostOutputsDoNotStarveVirtualProcessing()throws Exception{
  int count=Runtime.getRuntime().availableProcessors()+2;CountDownLatch entered=new CountDownLatch(count),release=new CountDownLatch(1),processed=new CountDownLatch(1);
  EventDispatcher dispatcher=new EventDispatcher(8,Duration.ofMillis(100));
  try{
   for(int i=0;i<count;i++)dispatcher.attachBlocking("console-"+i,()->new EventAdapter(){public synchronized void accept(ExecutionEvent event)throws Exception{entered.countDown();release.await();}},List.of(EventFilter.all()));
   new RunEvents("r",null,null,dispatcher,e->{}).event(null,null,"RUN_STARTED","Run started",Map.of(),null);
   assertTrue(entered.await(2,TimeUnit.SECONDS));Thread.ofVirtual().start(processed::countDown);assertTrue(processed.await(2,TimeUnit.SECONDS),"Blocked output must not occupy processing carriers");
  }finally{release.countDown();dispatcher.close();}
 }
 @Test void standaloneCancellationReportsStoppedRatherThanFailed()throws Exception{
  CountDownLatch entered=new CountDownLatch(1);
  ApplicationServices.Workflow workflow=(input,events)->{Plugin plugin=new AbstractPlugin(events.prepareAttempt("a","fixture",1)){
   public PluginManifest manifest(){return new PluginManifest("fixture","Fixture","1.0.0","1.0",getClass().getName(),"String","String");}
   public Object execute(Object value){entered.countDown();try{Thread.sleep(10000);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new CancellationException("interrupted");}return value;}
  };try(Pipeline pipeline=new Pipeline(List.of(plugin),events,List.of("a"))){return pipeline.execute(input);}};
  try(var services=new ApplicationServices(Map.of("query",workflow),Map.of("query",DESCRIPTOR),(mode,dispatcher,descriptor,state)->{},Map.of())){
   String id=services.start("query","sample");assertTrue(entered.await(2,TimeUnit.SECONDS));assertTrue(services.cancel(id));
   var session=services.get(id);for(int i=0;i<100&&!"CANCELLED".equals(session.snapshot().get("status"));i++)Thread.sleep(10);
   assertEquals("CANCELLED",session.snapshot().get("status"));var events=session.state.after(0);
   assertTrue(events.stream().anyMatch(e->e.type().equals("NODE_CANCELLED")));assertTrue(events.stream().anyMatch(e->e.type().equals("ATTEMPT_TERMINATED")));assertFalse(events.stream().anyMatch(e->e.type().equals("NODE_FAILED")));
  }
 }
 @Test void uiProjectionFiltersDetailsWithoutCreatingSequenceHolesOrHidingAuthoritativeState(){
  var state=new WorkflowStateStore("r",DESCRIPTOR,20);var events=new RunEvents("r","p",7,null,state::accept);
  events.event(null,null,"RUN_STARTED","started",Map.of(),null);var scope=events.attempt("a","fixture",1);scope.progress(4,10,"chunks");scope.finish("ATTEMPT_SUCCEEDED","done",2);events.event("a","fixture","NODE_SUCCEEDED","done",Map.of(),2L);
  var view=new EventView(List.of(new EventFilter(Set.of("b"),null,null)),false);var raw=state.after(0).stream().map(ExecutionEvent::toMap).toList();var snapshot=view.snapshot(state.snapshot(),raw);
  Map<?,?> node=(Map<?,?>)((Map<?,?>)snapshot.get("nodes")).get("a");assertEquals("SUCCEEDED",node.get("status"));assertFalse(node.containsKey("progress"));
  assertEquals(raw.size(),raw.stream().map(view::event).count());assertEquals(3L,view.event(raw.get(2)).get("sequence"));assertEquals(false,view.event(raw.get(2)).get("visible"));assertEquals(Map.of(),view.event(raw.get(2)).get("payload"));
 }
 @Test void selectedProgressSurvivesHistoryEvictionAndResetsOnRetry(){
  var state=new WorkflowStateStore("r",DESCRIPTOR,3);var events=new RunEvents("r","p",7,null,state::accept);var scope=events.attempt("a","fixture",1);
  scope.progress(4,10,"chunks");scope.report(new PluginMessage("progress","WARN","progress",Map.of("completed",7L,"total",10L,"unit","chunks")));
  for(int i=0;i<10;i++)scope.emit("detail",Map.of("count",i));
  var view=new EventView(List.of(new EventFilter(Set.of("a"),Set.of("PROGRESS"),Set.of("INFO"))),false);
  var snapshot=view.snapshot(state.snapshot(),state.after(0).stream().map(ExecutionEvent::toMap).toList());Map<?,?> node=(Map<?,?>)((Map<?,?>)snapshot.get("nodes")).get("a");
  assertEquals(4L,((Map<?,?>)node.get("progress")).get("completed"));assertFalse(node.containsKey("progressBySeverity"));assertTrue(state.gap(1));
  events.attempt("a","fixture",2);snapshot=view.snapshot(state.snapshot(),state.after(0).stream().map(ExecutionEvent::toMap).toList());node=(Map<?,?>)((Map<?,?>)snapshot.get("nodes")).get("a");assertFalse(node.containsKey("progress"));
 }
 @Test void templateArchivesRejectTraversalExecutableCodeAndDuplicateEntries()throws Exception{
  String manifest="{\"schemaVersion\":1,\"id\":\"sample\",\"name\":\"Sample\",\"version\":\"1\",\"html\":\"index.html\",\"css\":\"styles.css\",\"executable\":false,\"assets\":[]}";
  Map<String,String> files=new LinkedHashMap<>(Map.of("manifest.json",manifest,"index.html","<main>Safe text</main>","styles.css","main {color: blue}"));
  TemplateBundle bundle=TemplateBundle.read(zip(files),false);assertTrue(bundle.id().startsWith("sample-"));
  files.put("index.html","<meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><main>Safe</main>");assertNotNull(TemplateBundle.read(zip(files),false));
  files.put("index.html","<meta http-equiv=\"refresh\" content=\"0;url=/api/v1/pipelines\">");assertThrows(IllegalArgumentException.class,()->TemplateBundle.read(zip(files),false));
  files.put("index.html","<main>Safe text</main>");
  files.put("../outside.txt","bad");assertThrows(IllegalArgumentException.class,()->TemplateBundle.read(zip(files),false));files.remove("../outside.txt");
  files.put("index.html","<img src=x onerror=alert(1)>");assertThrows(IllegalArgumentException.class,()->TemplateBundle.read(zip(files),false));
  files.put("manifest.json",manifest.replace("false","true"));assertThrows(IllegalArgumentException.class,()->TemplateBundle.read(zip(files),false));assertNotNull(TemplateBundle.read(zip(files),true));
 }
 static byte[] zip(Map<String,String> files)throws IOException{ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(ZipOutputStream out=new ZipOutputStream(bytes)){for(var e:files.entrySet()){out.putNextEntry(new ZipEntry(e.getKey()));out.write(e.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8));out.closeEntry();}}return bytes.toByteArray();}
}

