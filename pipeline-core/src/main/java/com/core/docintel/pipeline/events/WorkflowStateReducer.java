package com.core.docintel.pipeline.events;
import java.util.*;
/** State only follows authoritative lifecycle types; domain messages cannot change status. */
public final class WorkflowStateReducer {
 private final String runId;private final PipelineDescriptor descriptor;private long sequence;private String status="WAITING";private final Map<String,Map<String,Object>> nodes=new LinkedHashMap<>();
 public WorkflowStateReducer(String runId,PipelineDescriptor descriptor){this.runId=runId;this.descriptor=descriptor;for(var n:descriptor.nodes())nodes.put(n.id(),new LinkedHashMap<>(Map.of("id",n.id(),"status","WAITING","attempt",0)));}
 public synchronized void apply(ExecutionEvent e){if(!runId.equals(e.runId())||e.sequence()<=sequence)return;sequence=e.sequence();
  if(e.nodeId()==null){switch(e.type()){case "RUN_STARTED"->status="RUNNING";case "RUN_SUCCEEDED"->status="SUCCEEDED";case "RUN_FAILED"->status="FAILED";case "CANCELLATION_REQUESTED"->status="CANCELLATION_REQUESTED";case "RUN_CANCELLED"->status="CANCELLED";default->{}}return;}
  Map<String,Object> n=nodes.get(e.nodeId());if(n==null)return;int current=((Number)n.get("attempt")).intValue();if(e.attempt()<current||e.attempt()==current&&n.get("attemptId")!=null&&!Objects.equals(e.attemptId(),n.get("attemptId")))return;
  if(e.type().equals("NODE_STARTED")&&e.payload().containsKey("inputSummary"))n.put("inputSummary",e.payload().get("inputSummary"));
  if(e.type().equals("NODE_SUCCEEDED")&&e.payload().containsKey("outputSummary"))n.put("outputSummary",e.payload().get("outputSummary"));
  if(e.type().equals("ATTEMPT_STARTED")){n.put("attempt",e.attempt());n.put("attemptId",e.attemptId());n.put("progress",Map.of());n.put("progressBySeverity",Map.of());n.put("status","RUNNING");n.put("terminationKnown",false);n.put("attemptClosed",false);}
  if(e.attemptId()!=null&&n.get("attemptId")!=null&&!e.attemptId().equals(n.get("attemptId")))return;
  if(e.type().equals("ATTEMPT_TERMINATED"))n.put("terminationKnown",true);
  if(Set.of("ATTEMPT_SUCCEEDED","ATTEMPT_FAILED","ATTEMPT_TIMEOUT","ATTEMPT_TERMINATED").contains(e.type()))n.put("attemptClosed",true);
  switch(e.type()){case "NODE_STARTED"->n.put("status","RUNNING");case "NODE_SUCCEEDED"->n.put("status","SUCCEEDED");case "NODE_FAILED"->n.put("status","FAILED");case "NODE_RETRYING"->n.put("status","RETRYING");case "CANCELLATION_REQUESTED"->n.put("status","CANCELLATION_REQUESTED");case "NODE_CANCELLED"->n.put("status","CANCELLED");case "PROGRESS"->{if(!Boolean.TRUE.equals(n.get("attemptClosed")))rememberProgress(n,e);}default->{}}
  if(e.durationMillis()!=null)n.put("durationMillis",e.durationMillis());
 }
 @SuppressWarnings("unchecked") private static void rememberProgress(Map<String,Object> node,ExecutionEvent event){
  node.put("progress",event.payload());
  if(!Set.of("INFO","WARN","ERROR","DEBUG","TRACE").contains(event.severity()))return;
  Map<String,Object> record=new LinkedHashMap<>();record.put("nodeId",event.nodeId());record.put("attemptId",event.attemptId());record.put("type","PROGRESS");record.put("severity",event.severity());record.put("sequence",event.sequence());record.put("payload",event.payload());
  Map<String,Object> records=new LinkedHashMap<>((Map<String,Object>)node.getOrDefault("progressBySeverity",Map.of()));records.put(event.severity(),Collections.unmodifiableMap(record));node.put("progressBySeverity",Collections.unmodifiableMap(records));
 }
 public synchronized Map<String,Object> snapshot(){Map<String,Object> result=new LinkedHashMap<>();result.put("runId",runId);result.put("descriptor",descriptor.toMap());result.put("sequence",sequence);result.put("status",status);Map<String,Object> copy=new LinkedHashMap<>();nodes.forEach((id,value)->copy.put(id,new LinkedHashMap<>(value)));result.put("nodes",copy);return result;}
}

