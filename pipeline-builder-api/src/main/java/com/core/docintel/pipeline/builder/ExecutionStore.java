package com.core.docintel.pipeline.builder;

import java.util.List;
import java.util.Optional;

public interface ExecutionStore {
    default void persistTelemetry(String id,java.util.Map<String,Object> snapshot,List<com.core.docintel.pipeline.events.ExecutionEvent> events,List<ExecutionEvent> legacy){events.forEach(event->saveMonitor(id,snapshot,event));legacy.forEach(event->append(id,event));}
    default void saveMonitor(String id,java.util.Map<String,Object> snapshot,com.core.docintel.pipeline.events.ExecutionEvent event){}
    default Optional<java.util.Map<String,Object>> monitor(String id){return Optional.empty();}
    default List<java.util.Map<String,Object>> runtimeEvents(String id,long after){return List.of();}
    void save(ExecutionSnapshot snapshot);
    void append(String executionId, ExecutionEvent event);
    Optional<ExecutionSnapshot> get(String id);
    List<ExecutionSnapshot> list();
    List<ExecutionEvent> events(String id);
    default List<ExecutionEvent> events(String id,long afterSequence){
        return events(id).stream().filter(event->event.sequence()>afterSequence).toList();
    }
}
