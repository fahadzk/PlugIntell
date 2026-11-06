package com.core.docintel.pipeline.builder;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class NodeTaskRunnerTest {
    @Test void retriesTransientFailureWithAttemptEvents()throws Exception{
        try(var runner=new NodeTaskRunner()){
            AtomicInteger calls=new AtomicInteger();List<String> states=new CopyOnWriteArrayList<>();
            String result=runner.execute(new NodeExecutionPolicy(2,0,0,1000),()->{
                if(calls.incrementAndGet()<3)throw new IllegalStateException("temporary failure");return "done";
            },(state,message,duration)->states.add(state));
            assertEquals("done",result);assertEquals(3,calls.get());assertEquals(2,states.stream().filter("RETRYING"::equals).count());assertEquals("SUCCEEDED",states.getLast());
        }
    }

    @Test void timesOutAndInterruptsCooperativeOperation() {
        try(var runner=new NodeTaskRunner()){
            List<String> states=new CopyOnWriteArrayList<>();
            IllegalStateException failure=assertThrows(IllegalStateException.class,()->runner.execute(new NodeExecutionPolicy(0,0,0,30),()->{TimeUnit.SECONDS.sleep(5);return "late";},(state,message,duration)->states.add(state)));
            assertTrue(failure.getMessage().contains("timed out"));assertEquals("FAILED",states.getLast());
        }
    }
}
