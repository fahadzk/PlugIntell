package com.core.docintel.pipeline.api;

import java.util.Map;

/** Optional per-node configuration hook. A new plugin instance is created for each execution node. */
public interface ConfigurablePlugin extends Plugin {
    void configure(Map<String, Object> configuration);
}
