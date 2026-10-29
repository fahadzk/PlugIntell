package com.core.docintel.pipeline.api;

public interface Plugin {
    PluginManifest manifest();
    Object execute(Object input);
}
