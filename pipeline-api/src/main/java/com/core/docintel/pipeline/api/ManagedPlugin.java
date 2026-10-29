package com.core.docintel.pipeline.api;

/** Optional lifecycle hook for plugins that own clients or other resources. */
public interface ManagedPlugin extends Plugin, AutoCloseable {
    @Override
    void close();
}
