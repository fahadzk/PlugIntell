package com.core.docintel.pipeline.api;
/** Allocation-free default reporting dependency for existing plugins. */
public enum NoopPluginMessages implements PluginMessages {
    INSTANCE;
    @Override public void report(PluginMessage message) { }
}
