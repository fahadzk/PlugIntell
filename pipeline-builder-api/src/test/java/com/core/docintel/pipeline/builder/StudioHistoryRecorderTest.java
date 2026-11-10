package com.core.docintel.pipeline.builder;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class StudioHistoryRecorderTest {
    @Test void idleHistoryDestinationsDoNotStarveVirtualExecutionThreads() throws Exception {
        ExecutionStore store=new ExecutionStore(){
            public void save(ExecutionSnapshot value){}
            public void append(String id,ExecutionEvent value){}
            public Optional<ExecutionSnapshot> get(String id){return Optional.empty();}
            public List<ExecutionSnapshot> list(){return List.of();}
            public List<ExecutionEvent> events(String id){return List.of();}
        };
        List<StudioHistoryRecorder> recorders=new ArrayList<>();
        try {
            for(int i=0;i<Runtime.getRuntime().availableProcessors()+2;i++)recorders.add(new StudioHistoryRecorder(store));
            Thread.sleep(300); // Let every idle destination enter its wait before scheduling processing.
            CountDownLatch executed=new CountDownLatch(1);
            Thread.ofVirtual().start(executed::countDown);
            assertTrue(executed.await(2,TimeUnit.SECONDS),"Idle telemetry destinations must not occupy Java 21 execution carriers");
        } finally {recorders.forEach(StudioHistoryRecorder::close);}
    }
}
