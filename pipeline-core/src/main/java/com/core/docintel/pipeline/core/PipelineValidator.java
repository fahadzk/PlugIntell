package com.core.docintel.pipeline.core;

import com.core.docintel.pipeline.api.Plugin;
import com.core.docintel.pipeline.api.PluginManifest;
import com.core.docintel.pipeline.api.PreflightPlugin;
import com.core.docintel.pipeline.api.PreflightContext;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class PipelineValidator {
    public static final String SUPPORTED_API_VERSION = "1.0";

    public void validate(List<Plugin> plugins) {
        if (plugins == null || plugins.isEmpty()) throw new PluginValidationException("Invalid pipeline: at least one plugin JAR must be supplied.");
        Set<String> ids = new HashSet<>();
        for (Plugin plugin : plugins) {
            PluginManifest manifest = plugin.manifest();
            if (!ids.add(manifest.id())) throw new PluginValidationException("Invalid pipeline: duplicate plugin ID '" + manifest.id() + "'.");
            if (!SUPPORTED_API_VERSION.equals(manifest.apiVersion())) throw new PluginValidationException("Invalid pipeline: plugin '" + manifest.id() + "' uses API version " + manifest.apiVersion() + "; supported version is " + SUPPORTED_API_VERSION + ".");
        }
        for (int i = 1; i < plugins.size(); i++) {
            PluginManifest previous = plugins.get(i - 1).manifest();
            PluginManifest next = plugins.get(i).manifest();
            if (!previous.outputType().equals(next.inputType())) {
                throw new PluginValidationException("Invalid pipeline:\n\nPlugin: " + next.id() + "\nExpected input: " + next.inputType() + "\nPrevious plugin output: " + previous.outputType() + "\n\nCannot connect " + next.id() + " after " + previous.id() + ".");
            }
        }
        // External checks run only after every static contract check has passed.
        PreflightContext context = new PreflightContext();
        for (Plugin plugin : plugins) {
            if (plugin instanceof PreflightPlugin preflightPlugin) {
                try {
                    preflightPlugin.preflight(context);
                } catch (RuntimeException e) {
                    throw new PluginValidationException("Preflight failed for plugin '"
                            + plugin.manifest().id() + "': " + e.getMessage(), e);
                }
            }
        }
    }
}
