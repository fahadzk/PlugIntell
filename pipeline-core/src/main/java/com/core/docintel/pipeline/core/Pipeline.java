package com.core.docintel.pipeline.core;

import com.core.docintel.pipeline.api.Plugin;
import com.core.docintel.pipeline.api.ManagedPlugin;
import java.util.List;

public final class Pipeline implements AutoCloseable {
    private final List<Plugin> plugins;
    public Pipeline(List<Plugin> plugins) { this.plugins = List.copyOf(plugins); }
    public Object execute(Object initialInput) {
        Object current = initialInput;
        for (Plugin plugin : plugins) {
            current = plugin.execute(current);
            System.out.printf("%-18s OK%n", plugin.manifest().id());
        }
        return current;
    }

    @Override
    public void close() {
        RuntimeException failure = null;
        for (int i = plugins.size() - 1; i >= 0; i--) {
            if (plugins.get(i) instanceof ManagedPlugin managed) {
                try {
                    managed.close();
                } catch (RuntimeException e) {
                    if (failure == null) failure = new IllegalStateException("Could not close pipeline plugin resources.", e);
                    else failure.addSuppressed(e);
                }
            }
        }
        if (failure != null) throw failure;
    }
}
