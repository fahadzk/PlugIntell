package com.core.docintel.pipeline.events;
import com.core.docintel.pipeline.api.*;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;
/** Per-run serial sequencer. Reporter identities are immutable and explicitly passed across executors. */
public final class RunEvents {
 private final String runId,pipelineId;private final Integer version;private final EventDispatcher dispatcher;private final Consumer<ExecutionEvent> recorder;private final EventSanitizer sanitizer=new EventSanitizer();private final Map<String,Attempt> attempts=new HashMap<>();private long sequence,late,recordingFailures,invalidMessages;private boolean cancellationRequested;
 public RunEvents(String runId,String pipelineId,Integer version,EventDispatcher dispatcher,Consumer<ExecutionEvent> recorder){this.runId=runId;this.pipelineId=pipelineId;this.version=version;this.dispatcher=dispatcher;this.recorder=recorder;}
 public String runId(){return runId;}public EventSanitizer sanitizer(){return sanitizer;}public synchronized long lateEvents(){return late;}
 public synchronized Map<String,Object> diagnostics(){return Map.of("lateEvents",late,"recordingFailures",recordingFailures,"invalidMessages",invalidMessages);}
 public synchronized void closeReports(){attempts.values().forEach(attempt->attempt.active=false);}
 public synchronized boolean cancellationRequested(){return cancellationRequested;}
 public synchronized Attempt prepareAttempt(String nodeId,String pluginId,int number){Attempt old=attempts.get(nodeId);if(old!=null)old.active=false;Attempt a=new Attempt(nodeId,pluginId,number);attempts.put(nodeId,a);return a;}
 public synchronized Attempt attempt(String nodeId,String pluginId,int number){Attempt a=prepareAttempt(nodeId,pluginId,number);a.start();return a;}
 public synchronized Attempt currentAttempt(String nodeId){return attempts.get(nodeId);}
 public synchronized void event(String nodeId,String pluginId,String type,String message,Map<String,Object> payload,Long duration){if(type.equals("CANCELLATION_REQUESTED"))cancellationRequested=true;Attempt a=attempts.get(nodeId);emit(nodeId,pluginId,a==null?null:a.id,a==null?0:a.number,type,type.contains("FAILED")||type.contains("TIMEOUT")?"ERROR":"INFO",type.toLowerCase(Locale.ROOT),message,payload,duration);}
 private synchronized void emit(String node,String plugin,String attemptId,int attempt,String type,String severity,String code,String message,Map<String,Object> payload,Long duration){
  ExecutionEvent event=new ExecutionEvent(1,UUID.randomUUID().toString(),runId,pipelineId,version,node,plugin,attemptId,attempt,++sequence,Instant.now(),type,severity,sanitizer.text(code),sanitizer.text(message),sanitizer.payload(payload),duration);
  try{recorder.accept(event);}catch(RuntimeException ignored){recordingFailures++;}if(dispatcher!=null)dispatcher.dispatch(event);
 }
 public final class Attempt implements PluginMessages,AutoCloseable{
  private final String node,plugin,id=UUID.randomUUID().toString();private final int number;private boolean active=true,outcomeReported;
  private Attempt(String node,String plugin,int number){this.node=node;this.plugin=plugin;this.number=number;}
  public String id(){return id;}
  @Override public PluginExecutionContext executionContext(){return PluginExecutionContext.fromHost(runId,pipelineId,version,node);}
  public void lifecycle(String type,String message,long duration){synchronized(RunEvents.this){RunEvents.this.emit(node,plugin,id,number,type,type.contains("FAILED")||type.contains("TIMEOUT")?"ERROR":"INFO",type.toLowerCase(Locale.ROOT),message,Map.of(),duration);}}
  public void start(){synchronized(RunEvents.this){RunEvents.this.emit(node,plugin,id,number,"ATTEMPT_STARTED","INFO","attempt.started","Attempt started",Map.of(),null);}}
  @Override public void report(PluginMessage message){synchronized(RunEvents.this){if(!active||attempts.get(node)!=this){late++;return;}try{if(message==null||!Set.of("INFO","WARN","ERROR","DEBUG","TRACE").contains(message.severity())){invalidMessages++;return;}RunEvents.this.emit(node,plugin,id,number,"progress".equals(message.code())?"PROGRESS":"PLUGIN_MESSAGE",message.severity(),message.code(),message.message(),message.payload(),null);}catch(RuntimeException failure){invalidMessages++;}}}
  public void finish(String type,String message,long duration){synchronized(RunEvents.this){if(outcomeReported)return;outcomeReported=true;RunEvents.this.emit(node,plugin,id,number,type,type.contains("FAILED")||type.contains("TIMEOUT")?"ERROR":"INFO",type.toLowerCase(Locale.ROOT),message,Map.of(),duration);active=false;}}
  @Override public void close(){synchronized(RunEvents.this){active=false;}}
 }
}

