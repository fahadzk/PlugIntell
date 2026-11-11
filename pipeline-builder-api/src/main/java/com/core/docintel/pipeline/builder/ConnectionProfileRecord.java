package com.core.docintel.pipeline.builder;

import io.micronaut.serde.annotation.Serdeable;
import java.time.Instant;
import java.util.Map;

@Serdeable
public record ConnectionProfileRecord(String id, String name, String pluginId, String pluginVersion,
                                      long revision, Instant createdAt, Instant updatedAt,
                                      Map<String, Object> config,
                                      Map<String, ConnectionPluginSettings> pluginConfigs, boolean defaultProfile) {
    public ConnectionProfileRecord(String id,String name,String pluginId,String pluginVersion,long revision,Instant createdAt,Instant updatedAt,Map<String,Object> config,Map<String,ConnectionPluginSettings> pluginConfigs) {
        this(id,name,pluginId,pluginVersion,revision,createdAt,updatedAt,config,pluginConfigs,false);
    }
}
