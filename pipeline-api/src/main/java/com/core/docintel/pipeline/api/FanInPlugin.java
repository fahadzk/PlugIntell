package com.core.docintel.pipeline.api;

import java.util.List;

/** Explicit contract for plugins that combine outputs from multiple incoming graph edges. */
public interface FanInPlugin extends Plugin {
    Object executeInputs(List<Object> inputs);

    @Override
    default Object execute(Object input) {
        return executeInputs(List.of(input));
    }
}
