package com.core.docintel.pipeline.web;

import com.core.docintel.pipeline.events.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HostExecutionLogsTest {
    @TempDir Path temporary;
    @Test void hostLogsAllRunsProgressAndFailuresWithoutPayloadContentsOrSecrets() throws Exception {
        var descriptor=new PipelineDescriptor("pipeline",1,"QUERY_PROCESSING",List.of(new PipelineDescriptor.Node("work","fixture","Work",List.of())),List.of());
        ApplicationServices.Workflow workflow=(input,events)->{
            events.sanitizer().register("synthetic-secret-value");
            var attempt=events.attempt("work","fixture",1);attempt.emit("embedding.started",Map.of("chunkCount",2,"prompt","private-document-body"));attempt.progress(1,2,"chunks");
            if(input.equals("fail"))throw new IllegalStateException("Synthetic failure synthetic-secret-value");
            attempt.finish("ATTEMPT_SUCCEEDED","Completed",1);return "private-answer-body";
        };
        String firstId,failedId;
        try(var logs=new HostExecutionLogs(temporary.resolve("logs"));var services=new ApplicationServices(Map.of("query",workflow),Map.of("query",descriptor),(mode,dispatcher,graph,state)->{},Map.of())){
            services.addEventDestination("_host-console",logs::adapter);
            var first=services.create("query");firstId=first.id;assertEquals("private-answer-body",services.execute(first,"input"));
            var failed=services.create("query");failedId=failed.id;assertThrows(IllegalStateException.class,()->services.execute(failed,"fail"));
        }
        String text=Files.readString(temporary.resolve("logs/server-0.log"));
        assertTrue(text.contains("run="+firstId));assertTrue(text.contains("run="+failedId));assertTrue(text.contains("RUN_STARTED"));assertTrue(text.contains("RUN_SUCCEEDED"));assertTrue(text.contains("RUN_FAILED"));assertTrue(text.contains("PROGRESS"));assertTrue(text.contains("completed=1"));assertTrue(text.contains("total=2"));assertTrue(text.contains("node=work"));assertTrue(text.contains("attempt=1/"));
        assertFalse(text.contains("private-document-body"));assertFalse(text.contains("private-answer-body"));assertFalse(text.contains("synthetic-secret-value"));assertTrue(text.contains("[REDACTED]"));
    }
    @Test void unwritableLogDestinationKeepsConsoleLoggingAvailable() throws Exception {
        Path file=temporary.resolve("file");Files.writeString(file,"existing");
        try(var logs=new HostExecutionLogs(file.resolve("logs"))){logs.info("Console remains available");}
        assertEquals("existing",Files.readString(file));
    }
}
