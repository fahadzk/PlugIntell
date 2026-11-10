package com.core.docintel.pipeline.builder.fixtures;

import com.core.docintel.pipeline.api.*;
import java.util.*;

/** Offline equivalent of the answer generator's actual output contract. */
public final class AnswerFixture extends AbstractPlugin {
    public AnswerFixture() {}
    public AnswerFixture(PluginMessages messages) { super(messages); }
    public PluginManifest manifest() { return new PluginManifest("fixture-answer", "Fixture answer", "1.0.0", "1.0", getClass().getName(), "SearchRequest", "AnswerResult"); }
    public Object execute(Object input) {
        emit("fixture.query", Map.of("count", 1));
        return new AnswerResult(((SearchRequest)input).query(), "UAE answer <script> rendered as text", true,
            List.of(new RetrievedChunk("citation-1", "private source text must not escape", "Middle East.pdf", 7, .9)));
    }
}
