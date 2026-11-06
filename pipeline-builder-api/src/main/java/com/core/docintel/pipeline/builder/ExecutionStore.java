package com.core.docintel.pipeline.builder;

import java.util.List;
import java.util.Optional;

public interface ExecutionStore {
    void save(ExecutionSnapshot snapshot);
    void append(String executionId, ExecutionEvent event);
    Optional<ExecutionSnapshot> get(String id);
    List<ExecutionSnapshot> list();
    List<ExecutionEvent> events(String id);
    default List<ExecutionEvent> events(String id,long afterSequence){
        return events(id).stream().filter(event->event.sequence()>afterSequence).toList();
    }
}
