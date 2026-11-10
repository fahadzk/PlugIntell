package com.core.docintel.pipeline.events;
import java.util.*;
/** Bounded standalone memory history; Studio adds SQLite storage. */
public final class WorkflowStateStore {
 private final int eventLimit;private final WorkflowStateReducer reducer;private final ArrayDeque<ExecutionEvent> events=new ArrayDeque<>();
 public WorkflowStateStore(String runId,PipelineDescriptor descriptor,int eventLimit){if(eventLimit<1)throw new IllegalArgumentException("eventLimit must be positive");this.eventLimit=eventLimit;reducer=new WorkflowStateReducer(runId,descriptor);}
 public synchronized void accept(ExecutionEvent event){reducer.apply(event);if(!events.isEmpty()&&event.sequence()<=events.getLast().sequence())return;events.addLast(event);while(events.size()>eventLimit)events.removeFirst();}
 public synchronized List<ExecutionEvent> after(long sequence){return events.stream().filter(e->e.sequence()>sequence).toList();}
 public synchronized boolean gap(long cursor){return !events.isEmpty()&&(cursor<events.getFirst().sequence()-1||cursor>events.getLast().sequence());}
 public synchronized Map<String,Object> snapshot(){Map<String,Object> m=new LinkedHashMap<>(reducer.snapshot());m.put("events",events.stream().skip(Math.max(0,events.size()-200)).map(ExecutionEvent::toMap).toList());m.put("oldestSequence",events.isEmpty()?0:events.getFirst().sequence());return m;}
}

