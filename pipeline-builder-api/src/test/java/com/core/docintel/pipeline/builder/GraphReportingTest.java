package com.core.docintel.pipeline.builder;
import com.core.docintel.pipeline.builder.fixtures.ReportingFixture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import static org.junit.jupiter.api.Assertions.*;
class GraphReportingTest {
 @TempDir Path temp;
 @Test void parallelNodesConcurrentRunsAndRetryScopesRemainIndependent()throws Exception{
  try(Harness h=harness()){
   var root=node("root",Map.of(),NodeExecutionPolicy.defaults());
   var left=node("left",Map.of("failFirst",true,"delay",120),new NodeExecutionPolicy(1,0,0,10000));
   var right=node("right",Map.of("delay",120),NodeExecutionPolicy.defaults());
   var definition=new PipelineDefinition("Parallel",PipelineType.QUERY_PROCESSING,1,List.of(root,left,right),List.of(new PipelineEdge("root","left"),new PipelineEdge("root","right")),null,Map.of("template",Map.of("id","water","version","1")));
   var p=h.control.create(definition);h.control.publish(p.id(),p.revision());
   String a=h.manager.start(new ExecutionRequest(p.id(),1,"question",null,null,null)).executionId();
   String b=h.manager.start(new ExecutionRequest(p.id(),1,"another",null,null,null)).executionId();
   terminal(h,a);terminal(h,b);
   assertEquals(ExecutionStatus.SUCCEEDED,h.manager.get(a).orElseThrow().status(),h.manager.get(a).orElseThrow().error());assertEquals(ExecutionStatus.SUCCEEDED,h.manager.get(b).orElseThrow().status(),h.manager.get(b).orElseThrow().error());
   for(String id:List.of(a,b)){
    var events=h.manager.runtimeEvents(id,0);assertTrue(events.stream().allMatch(e->id.equals(e.get("runId"))));assertTrue(events.stream().anyMatch(e->"NODE_RETRYING".equals(e.get("type"))));
    assertTrue(((Number)h.manager.monitor(id).get("lateEvents")).longValue()>0);
    assertFalse(events.stream().anyMatch(e->"fixture.obsolete".equals(e.get("code"))));
    assertEquals(events.size(),events.stream().map(e->e.get("sequence")).distinct().count());
    Map<?,?> nodes=(Map<?,?>)h.manager.monitor(id).get("nodes");assertEquals(2,((Number)((Map<?,?>)nodes.get("left")).get("attempt")).intValue());
    var firstLeft=events.stream().filter(e->"left".equals(e.get("nodeId"))&&"ATTEMPT_STARTED".equals(e.get("type"))).findFirst().orElseThrow();
    var startRight=events.stream().filter(e->"right".equals(e.get("nodeId"))&&"ATTEMPT_STARTED".equals(e.get("type"))).findFirst().orElseThrow();
    var endLeft=events.stream().filter(e->"left".equals(e.get("nodeId"))&&"NODE_SUCCEEDED".equals(e.get("type"))).findFirst().orElseThrow();
    assertTrue(((Number)startRight.get("sequence")).longValue()<((Number)endLeft.get("sequence")).longValue());
   }
   h.manager.close(); // The bounded persistence destination drains before restart verification.
   var reopened=new SqliteExecutionStore(h.db.toString());reopened.initialize();assertEquals("water",((Map<?,?>)reopened.monitor(a).orElseThrow().get("template")).get("id"));assertFalse(reopened.runtimeEvents(a,0).isEmpty());
   assertTrue(h.manager.events(a).stream().allMatch(e->e.status()!=null)); // old polling shape still present
   long savedSequence=((Number)reopened.monitor(a).orElseThrow().get("sequence")).longValue();
   try(var connection=java.sql.DriverManager.getConnection("jdbc:sqlite:"+h.db);var statement=connection.prepareStatement("DELETE FROM runtime_events WHERE execution_id=? AND sequence=?")){statement.setString(1,a);statement.setLong(2,savedSequence);statement.executeUpdate();}
   var restarted=new GraphExecutionManager(h.control,h.catalog,h.validator,reopened,temp.toString());try{assertTrue(restarted.historyGap(a,savedSequence-1),"A lost buffered tail must produce an explicit reconnect gap");}finally{restarted.close();}
  }
 }
 @Test void cancellationRemainsRequestedUntilIgnoringPluginActuallyStops()throws Exception{
  try(Harness h=harness()){
   var p=h.control.create(new PipelineDefinition("Cancel",PipelineType.QUERY_PROCESSING,1,List.of(node("slow",Map.of("delay",1400,"ignoreInterrupt",true),new NodeExecutionPolicy(0,0,0,5000))),List.of()));h.control.publish(p.id(),p.revision());
   String id=h.manager.start(new ExecutionRequest(p.id(),1,"question",null,null,null)).executionId();
   for(int i=0;i<200&&h.manager.runtimeEvents(id,0).stream().noneMatch(e->"PROGRESS".equals(e.get("type")));i++)Thread.sleep(5);
   assertTrue(h.manager.cancel(id));Thread.sleep(60);assertEquals(ExecutionStatus.CANCELLING,h.manager.get(id).orElseThrow().status());
   terminal(h,id);assertEquals(ExecutionStatus.CANCELLED,h.manager.get(id).orElseThrow().status());
   var events=h.manager.runtimeEvents(id,0);assertTrue(events.stream().anyMatch(e->"CANCELLATION_REQUESTED".equals(e.get("type"))));assertTrue(events.stream().anyMatch(e->"ATTEMPT_TERMINATED".equals(e.get("type"))));assertTrue(events.stream().anyMatch(e->"RUN_CANCELLED".equals(e.get("type"))));
  }
 }
 @Test void blockedAndFailingTelemetryStorageDoesNotBlockOrFailProcessing()throws Exception{
  try(Harness h=harness()){
   var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
   ExecutionStore destination=new ExecutionStore(){
    public void save(ExecutionSnapshot snapshot){h.executions.save(snapshot);}
    public void append(String id,ExecutionEvent event){h.executions.append(id,event);}
    public Optional<ExecutionSnapshot> get(String id){return h.executions.get(id);}
    public List<ExecutionSnapshot> list(){return h.executions.list();}
    public List<ExecutionEvent> events(String id){return h.executions.events(id);}
    public void persistTelemetry(String id,Map<String,Object> snapshot,List<com.core.docintel.pipeline.events.ExecutionEvent> events,List<ExecutionEvent> legacy){entered.countDown();try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}throw new IllegalStateException("Synthetic telemetry storage failure");}
   };
   GraphExecutionManager manager=new GraphExecutionManager(h.control,h.catalog,h.validator,destination,temp.toString());
   try{
    var p=h.control.create(new PipelineDefinition("Storage isolation",PipelineType.QUERY_PROCESSING,1,List.of(node("node",Map.of(),NodeExecutionPolicy.defaults())),List.of()));h.control.publish(p.id(),p.revision());
    String id=manager.start(new ExecutionRequest(p.id(),1,"question",null,null,null)).executionId();assertTrue(entered.await(2,java.util.concurrent.TimeUnit.SECONDS));
    for(int i=0;i<200&&manager.get(id).orElseThrow().status()!=ExecutionStatus.SUCCEEDED;i++)Thread.sleep(10);
    assertEquals(ExecutionStatus.SUCCEEDED,manager.get(id).orElseThrow().status());assertFalse(manager.events(id).isEmpty());
    release.countDown();boolean observed=false;for(int i=0;i<100;i++){List<?> health=(List<?>)manager.monitor(id).get("health");observed=health.stream().map(value->(Map<?,?>)value).anyMatch(value->"_studio-history".equals(value.get("id"))&&((Number)value.get("failed")).longValue()>0);if(observed)break;Thread.sleep(10);}
    assertTrue(observed);assertEquals(ExecutionStatus.SUCCEEDED,manager.get(id).orElseThrow().status());
   }finally{release.countDown();manager.close();}
  }
 }
 @Test void capabilitiesValidateReferencesAndPublishedPinsStayImmutable()throws Exception{
  try(Harness h=harness()){
   var base=new PipelineDefinition("Capabilities",PipelineType.QUERY_PROCESSING,1,List.of(node("node",Map.of(),NodeExecutionPolicy.defaults())),List.of());
   Map<String,Object> malformedTemplate=new HashMap<>();malformedTemplate.put("version",null);
   assertThrows(IllegalArgumentException.class,()->CapabilitySettings.validate(new PipelineDefinition(base.name(),base.type(),1,base.nodes(),base.edges(),null,Map.of("template",malformedTemplate)),false));
   Map<String,Object> invalidCapabilities=Map.of("adapters",List.of(Map.of("id","ui","type","ui","version","1")),"subscriptions",List.of(Map.of("adapterId","ui","workflow","QUERY_PROCESSING","nodeIds",List.of("missing"))));
   assertThrows(PipelineControlService.InvalidPipelineException.class,()->h.control.create(new PipelineDefinition(base.name(),base.type(),1,base.nodes(),base.edges(),null,invalidCapabilities)));
   Map<String,Object> c=Map.of("adapters",List.of(Map.of("id","ui","type","ui","version","1")),"subscriptions",List.of(Map.of("adapterId","ui","workflow","QUERY_PROCESSING","nodeIds",List.of("node"))),"template",Map.of("id","water","version","1"));
   var p=h.control.create(new PipelineDefinition(base.name(),base.type(),1,base.nodes(),base.edges(),null,c));h.control.publish(p.id(),p.revision());h.control.update(p.id(),h.control.get(p.id()).orElseThrow().revision(),base);
   assertEquals("water",((Map<?,?>)h.control.version(p.id(),1).orElseThrow().definition().capabilities().get("template")).get("id"));
   assertThrows(IllegalArgumentException.class,()->CapabilitySettings.validate(h.control.version(p.id(),1).orElseThrow().definition(),true));
  }
 }
 PipelineNode node(String id,Map<String,Object> config,NodeExecutionPolicy policy){return new PipelineNode(id,"reporting-fixture","1.0.0",config,policy);}
 void terminal(Harness h,String id)throws Exception{for(int i=0;i<600;i++){var status=h.manager.get(id).orElseThrow().status();if(Set.of(ExecutionStatus.SUCCEEDED,ExecutionStatus.FAILED,ExecutionStatus.CANCELLED).contains(status))return;Thread.sleep(10);}fail("Run did not terminate");}
 Harness harness()throws Exception{
  Path plugins=temp.resolve("plugins"),module=plugins.resolve("reporting-fixture");Files.createDirectories(module.resolve("target"));Files.createDirectories(module.resolve("src/main/resources/META-INF"));
  String yaml="plugin:\n  id: reporting-fixture\n  name: Reporting fixture\n  version: 1.0.0\n  apiVersion: '1.0'\n  entrypoint: "+ReportingFixture.class.getName()+"\n  workflows: [QUERY_PROCESSING]\n  input: {type: SearchRequest}\n  output: {type: SearchRequest}\n  capabilities: [parallel-safe]\n  configuration:\n    properties:\n      name: {type: string}\n      failFirst: {type: boolean}\n      ignoreInterrupt: {type: boolean}\n      delay: {type: integer}\n";
  Files.writeString(module.resolve("src/main/resources/META-INF/PluginManifest.yaml"),yaml);Files.writeString(module.resolve("pom.xml"),"<project><artifactId>reporting-fixture</artifactId></project>");
  try(JarOutputStream jar=new JarOutputStream(Files.newOutputStream(module.resolve("target/reporting-fixture.jar")))){jar.putNextEntry(new JarEntry("META-INF/PluginManifest.yaml"));jar.write(yaml.getBytes(java.nio.charset.StandardCharsets.UTF_8));jar.closeEntry();String resource=ReportingFixture.class.getName().replace('.','/')+".class";jar.putNextEntry(new JarEntry(resource));try(var stream=ReportingFixture.class.getClassLoader().getResourceAsStream(resource)){stream.transferTo(jar);}jar.closeEntry();}
  var catalog=new PluginCatalog(plugins.toString());Path db=temp.resolve("studio.db");var store=new SqlitePipelineStore(db.toString());store.initialize();var validator=new GraphPipelineValidator();var control=new PipelineControlService(store,validator,catalog);var executions=new SqliteExecutionStore(db.toString());executions.initialize();return new Harness(db,control,new GraphExecutionManager(control,catalog,validator,executions,temp.toString()),catalog,validator,executions);
 }
 record Harness(Path db,PipelineControlService control,GraphExecutionManager manager,PluginCatalog catalog,GraphPipelineValidator validator,SqliteExecutionStore executions)implements AutoCloseable{public void close(){manager.close();}}
}

