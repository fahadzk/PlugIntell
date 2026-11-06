package com.core.docintel.pipeline.builder;

import io.micronaut.serde.annotation.Serdeable;
import java.util.Map;

@Serdeable
public record ConnectionProfileRequest(String name, String pluginId, Map<String, Object> config,
                                       Long expectedRevision,
                                       Map<String, ConnectionPluginSettings> pluginConfigs) {
    public ConnectionProfileRequest(String name, String pluginId, Map<String, Object> config,
                                    Long expectedRevision) {
        this(name, pluginId, config, expectedRevision, null);
    }
}
