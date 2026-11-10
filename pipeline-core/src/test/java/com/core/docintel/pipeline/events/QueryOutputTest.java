package com.core.docintel.pipeline.events;

import com.core.docintel.pipeline.api.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class QueryOutputTest {
    private static final PipelineDescriptor DESCRIPTOR = new PipelineDescriptor("query", 1, "QUERY_PROCESSING", List.of(), List.of());
    private ApplicationServices services(ApplicationServices.Workflow workflow) {
        return new ApplicationServices(Map.of("query", workflow), Map.of("query", DESCRIPTOR), (m,d,p,s) -> {}, Map.of());
    }

    @Test void retainsAnswerAndCitationMetadataSeparatelyFromTelemetry() throws Exception {
        var answer = new AnswerResult("question", "Answer <script> as text", true,
            List.of(new RetrievedChunk("chunk-1", "private document passage", "report.pdf", 3, .8)));
        try (var services = services((input, events) -> answer)) {
            var session = services.create("query");
            assertEquals(false, session.result().get("available"));
            assertSame(answer, services.execute(session, "question"));
            var output = session.result();
            assertEquals("Answer <script> as text", output.get("answer"));
            assertEquals("question", output.get("query"));
            var citation = ((List<Map<String,Object>>)output.get("citations")).getFirst();
            assertEquals(Map.of("id", "chunk-1", "sourceName", "report.pdf", "pageNumber", 3, "score", .8), citation);
            for (Object telemetry : List.of(session.snapshot(), session.replay(0), services.history())) {
                assertFalse(telemetry.toString().contains(answer.answer()));
                assertFalse(telemetry.toString().contains("private document passage"));
            }
            assertFalse(output.toString().contains("private document passage"));
            assertThrows(UnsupportedOperationException.class, () -> output.put("answer", "altered"));
        }
    }

    @Test void isolatesConcurrentRunResultsAndWithholdsFailedCancelledAndUnsupportedOutputs() throws Exception {
        try (var services = services((input, events) -> {
            if (input.equals("failure")) throw new IllegalArgumentException("failure");
            if (input.equals("cancel")) throw new java.util.concurrent.CancellationException();
            return input.equals("unsupported") ? List.of("private") : input;
        })) {
            var a=services.create("query"); var b=services.create("query");
            try (var executor=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
                var first=executor.submit(() -> services.execute(a,"first answer"));
                var second=executor.submit(() -> services.execute(b,"second answer"));
                first.get();second.get();
            }
            assertEquals("first answer",a.result().get("answer"));
            assertEquals("second answer",b.result().get("answer"));
            for (String input:List.of("failure","cancel","unsupported")) {
                var session=services.create("query");
                try {services.execute(session,input);} catch (Exception expected) {}
                assertEquals(false,session.result().get("available"));
                assertFalse(session.result().containsKey("answer"));
            }
        }
    }

    @Test void boundsStoredOutputWithoutSplittingSurrogatesOrRetainingDocumentContents() {
        String longAnswer="x".repeat(QueryOutput.MAX_ANSWER_CHARACTERS-1)+"\uD83D\uDE00";
        var chunks=java.util.stream.IntStream.range(0,110).mapToObj(i->new RetrievedChunk("id"+i,"private","s".repeat(2000),1,.5)).toList();
        var result=QueryOutput.of(new AnswerResult("question",longAnswer,true,chunks));
        assertEquals(true,result.get("truncated"));
        assertEquals(QueryOutput.MAX_ANSWER_CHARACTERS-1,((String)result.get("answer")).length());
        assertEquals(true,result.get("citationsTruncated"));
        assertEquals(100,((List<?>)result.get("citations")).size());
        assertFalse(result.toString().contains("private"));
    }
}
