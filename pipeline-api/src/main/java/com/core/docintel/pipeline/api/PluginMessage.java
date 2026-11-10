package com.core.docintel.pipeline.api;
import java.util.Map;
/** Domain message only. Run, node and attempt identity belong to the runtime. */
public record PluginMessage(String code,String severity,String message,Map<String,Object> payload) {
    public PluginMessage { payload=payload==null?Map.of():java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(payload)); }
}

