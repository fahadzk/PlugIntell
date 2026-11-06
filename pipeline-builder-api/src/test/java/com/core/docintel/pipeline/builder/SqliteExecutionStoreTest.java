package com.core.docintel.pipeline.builder;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class SqliteExecutionStoreTest {
    @TempDir Path temp;
    @Test void persistsStatusAndNodeEventsAndMarksInterruptedRunsOnRestart(){
        SqliteExecutionStore first=store();Instant now=Instant.now();
        ExecutionSnapshot queued=new ExecutionSnapshot("exec-1","pipeline-1",1,ExecutionStatus.QUEUED,now,null,null,Map.of(),null);
        first.save(queued);first.append("exec-1",new ExecutionEvent(1,now,"node-1","RUNNING","Node started",0));first.append("exec-1",new ExecutionEvent(2,now,"node-1","SUCCEEDED","Node finished",12));
        SqliteExecutionStore reopened=store();
        assertEquals(ExecutionStatus.FAILED,reopened.get("exec-1").orElseThrow().status());
        assertTrue(reopened.get("exec-1").orElseThrow().error().contains("Server restarted"));
        assertEquals("node-1",reopened.events("exec-1").getFirst().nodeId());
        assertEquals(1,reopened.events("exec-1",1).size());
        assertEquals(2,reopened.events("exec-1",0).size());
    }
    private SqliteExecutionStore store(){SqliteExecutionStore store=new SqliteExecutionStore(temp.resolve("executions.db").toString());store.initialize();return store;}
}
