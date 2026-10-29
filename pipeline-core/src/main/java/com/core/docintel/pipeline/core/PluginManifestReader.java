package com.core.docintel.pipeline.core;

import com.core.docintel.pipeline.api.PluginManifest;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.jar.JarFile;

public final class PluginManifestReader {
    public static final String MANIFEST_PATH = "META-INF/PluginManifest.yaml";

    public PluginManifest read(Path jar) {
        String label = jar.getFileName() == null ? jar.toString() : jar.getFileName().toString();
        try {
            if (!Files.isRegularFile(jar)) throw invalid(label, "file does not exist or is not a regular file.");
            try (JarFile jarFile = new JarFile(jar.toFile())) {
                var entry = jarFile.getJarEntry(MANIFEST_PATH);
                if (entry == null) throw invalid(label, MANIFEST_PATH + " was not found.");
                try (InputStream in = jarFile.getInputStream(entry)) {
                    Object parsed = new Yaml(new SafeConstructor(new LoaderOptions())).load(in);
                    if (!(parsed instanceof Map<?, ?> root)) throw invalid(label, "YAML must contain a top-level 'plugin' section.");
                    Object pluginValue = root.get("plugin");
                    if (!(pluginValue instanceof Map<?, ?> plugin)) throw invalid(label, "YAML must contain a 'plugin' section.");
                    String id = required(plugin, "id", label);
                    String name = required(plugin, "name", label);
                    String version = required(plugin, "version", label);
                    String apiVersion = required(plugin, "apiVersion", label);
                    String entrypoint = required(plugin, "entrypoint", label);
                    String inputType = nestedType(plugin, "input", label);
                    String outputType = nestedType(plugin, "output", label);
                    return new PluginManifest(id, name, version, apiVersion, entrypoint, inputType, outputType);
                }
            }
        } catch (PluginValidationException e) {
            throw e;
        } catch (Exception e) {
            throw new PluginValidationException("Invalid plugin JAR:\n\n" + label + "\n\nReason:\n" + useful(e), e);
        }
    }

    private static String nestedType(Map<?, ?> map, String key, String label) {
        Object value = map.get(key);
        if (!(value instanceof Map<?, ?> nested)) throw invalid(label, "plugin." + key + ".type is required.");
        return required(nested, "type", label);
    }

    private static String required(Map<?, ?> map, String key, String label) {
        Object value = map.get(key);
        if (!(value instanceof String text) || text.isBlank()) throw invalid(label, "plugin." + key + " is required and must not be empty.");
        return text.trim();
    }

    private static PluginValidationException invalid(String label, String reason) {
        return new PluginValidationException("Invalid plugin JAR:\n\n" + label + "\n\nReason:\n" + reason);
    }

    private static String useful(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }
}
