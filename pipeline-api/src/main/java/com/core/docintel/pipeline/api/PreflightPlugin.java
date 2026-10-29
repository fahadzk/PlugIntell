package com.core.docintel.pipeline.api;

/** Optional plugin hook for checking external prerequisites before pipeline execution. */
public interface PreflightPlugin extends Plugin {
    void preflight();

    default void preflight(PreflightContext context) {
        preflight();
    }
}
