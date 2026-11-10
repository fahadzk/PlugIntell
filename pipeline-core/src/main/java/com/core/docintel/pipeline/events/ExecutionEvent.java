package com.core.docintel.pipeline.events;
import java.time.Instant;
import java.util.*;
public record ExecutionEvent(int schemaVersion,String eventId,String runId,String pipelineId,Integer pipelineVersion,
 String nodeId,String pluginId,String attemptId,int attempt,long sequence,Instant timestamp,String type,String severity,
 String code,String message,Map<String,Object> payload,Long durationMillis) {
 public boolean terminal(){return Set.of("RUN_SUCCEEDED","RUN_FAILED","RUN_CANCELLED","NODE_SUCCEEDED","NODE_FAILED","NODE_CANCELLED","ATTEMPT_SUCCEEDED","ATTEMPT_FAILED","ATTEMPT_TIMEOUT","ATTEMPT_TERMINATED").contains(type);}
 public Map<String,Object> toMap(){
  Map<String,Object> m=new LinkedHashMap<>();m.put("schemaVersion",schemaVersion);m.put("eventId",eventId);m.put("runId",runId);m.put("pipelineId",pipelineId);m.put("pipelineVersion",pipelineVersion);m.put("nodeId",nodeId);m.put("pluginId",pluginId);m.put("attemptId",attemptId);m.put("attempt",attempt);m.put("sequence",sequence);m.put("timestamp",timestamp.toString());m.put("type",type);m.put("severity",severity);m.put("code",code);m.put("message",message);m.put("payload",payload);m.put("durationMillis",durationMillis);return m;
 }
}

