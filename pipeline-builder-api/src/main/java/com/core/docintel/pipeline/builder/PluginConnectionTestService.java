package com.core.docintel.pipeline.builder;

import com.core.docintel.pipeline.api.*;
import com.core.docintel.pipeline.core.PluginLoader;
import jakarta.inject.Singleton;
import java.time.Instant;
import java.util.*;

/** Runs a configured plugin's preflight connection check without persisting resolved secrets. */
@Singleton
public final class PluginConnectionTestService {
    private static final NodeExecutionPolicy TEST_POLICY = new NodeExecutionPolicy(0, 0, 0, 30_000);
    private final PluginCatalog catalog;
    private final GraphPipelineValidator validator;
    private final NodeTaskRunner runner;

    public PluginConnectionTestService(PluginCatalog catalog, GraphPipelineValidator validator, NodeTaskRunner runner) {
        this.catalog = catalog;
        this.validator = validator;
        this.runner = runner;
    }

    public PluginConnectionTestResult test(String pluginId, Map<String, Object> config) {
        long started = System.nanoTime();
        PluginInfo info = catalog.all().stream().filter(value -> value.id().equals(pluginId)).findFirst()
                .orElseThrow(() -> new NoSuchElementException("Plugin not found: " + pluginId));
        Map<String, Object> configuration = config == null ? Map.of() : config;
        GraphValidation validation = validator.validatePluginConfiguration(pluginId, info.version(), configuration, catalog.all(), true);
        if (!validation.valid()) return new PluginConnectionTestResult(false, false, String.join("; ", validation.errors()), elapsed(started));

        SecretRedactor redactor = new SecretRedactor();
        try (PluginLoader loader = new PluginLoader()) {
            Plugin plugin = loader.load(catalog.pluginJar(info));
            try {
                if (plugin instanceof ConfigurablePlugin configurable) configurable.configure(resolveSecrets(configuration, redactor));
                else if (!configuration.isEmpty()) throw new IllegalArgumentException("Plugin does not accept configurable connection settings.");
                if (plugin instanceof PreflightPlugin preflight) {
                    runner.execute(TEST_POLICY, () -> { preflight.preflight(new PreflightContext()); return null; }, (state, message, duration) -> { });
                    return new PluginConnectionTestResult(true, true, "Plugin preflight connection check passed.", elapsed(started));
                }
                return new PluginConnectionTestResult(true, false, "Plugin loaded and configured; it does not provide a connection preflight check.", elapsed(started));
            } finally {
                if (plugin instanceof ManagedPlugin managed) managed.close();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return new PluginConnectionTestResult(false, true, "Plugin connection check was interrupted.", elapsed(started));
        } catch (Exception failure) {
            return new PluginConnectionTestResult(false, true, redactor.redact(safe(failure)), elapsed(started));
        }
    }

    private static Map<String, Object> resolveSecrets(Map<String, Object> config, SecretRedactor redactor) {
        return SecretReferences.resolve(config, redactor);
    }

    private static String safe(Throwable failure) {
        Throwable value = failure;
        while (value.getCause() != null && value.getCause() != value) value = value.getCause();
        String message = value.getMessage();
        if (message == null || message.isBlank()) message = value.getClass().getSimpleName();
        return message.length() > 500 ? message.substring(0, 500) : message;
    }

    private static long elapsed(long started) { return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started); }
}
