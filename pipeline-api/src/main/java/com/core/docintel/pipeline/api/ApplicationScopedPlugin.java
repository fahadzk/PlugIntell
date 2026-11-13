package com.core.docintel.pipeline.api;
/** Opt-in implementation-classloader lifetime for plugins owning application resources across runs. */
public interface ApplicationScopedPlugin extends ManagedPlugin {
    /** Application shutdown only; close() still denotes the end of an individual pipeline run. */
    void closeApplication();
}
