package com.core.docintel.plugins;

import com.core.docintel.pipeline.api.AnswerResult;
import com.core.docintel.pipeline.api.RetrievalResult;
import com.core.docintel.pipeline.api.RetrievedChunk;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class OllamaAnswerPluginTest {
    @Test void preservesAndReturnsCitationsWhenEnabled() {
        var service = new FakeAnswer();
        var result = (AnswerResult) new OllamaAnswerPlugin(service).execute(retrieval(true));
        assertEquals("The answer is in the text.", result.answer());
        assertEquals(1, result.citations().size());
        assertTrue(service.last.citationsEnabled());
    }

    @Test void omitsCitationOutputWhenDisabled() {
        var service = new FakeAnswer();
        var result = (AnswerResult) new OllamaAnswerPlugin(service).execute(retrieval(false));
        assertFalse(result.citationsEnabled());
        assertTrue(result.citations().isEmpty());
        assertFalse(service.last.citationsEnabled());
    }

    private static RetrievalResult retrieval(boolean citations) {
        return new RetrievalResult("what is it?", citations,
                List.of(new RetrievedChunk("id", "reference text", "manual.pdf", 4, 0.9)));
    }

    private static final class FakeAnswer implements OllamaAnswerPlugin.AnswerService {
        RetrievalResult last;
        public void preflight() {}
        public String answer(RetrievalResult result) { last = result; return "The answer is in the text."; }
        public void close() {}
    }
}
