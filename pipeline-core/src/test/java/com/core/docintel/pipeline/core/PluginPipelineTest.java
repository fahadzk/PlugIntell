package com.core.docintel.pipeline.core;

import com.core.docintel.pipeline.api.Plugin;
import com.core.docintel.pipeline.api.PluginManifest;
import com.core.docintel.pipeline.api.PreflightPlugin;
import com.core.docintel.pipeline.api.PreflightContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class PluginPipelineTest {
    @TempDir Path temp;

    @Test void validPluginJarLoadsFromItsOwnJar() throws Exception {
        Path jar = pluginJar("good.jar", GoodPlugin.class.getName(), "good", "File", "TextDocument", GoodPlugin.class, manifest("good", GoodPlugin.class.getName(), "File", "TextDocument"));
        try (PluginLoader loader = new PluginLoader()) { assertEquals("good", loader.load(jar).manifest().id()); }
    }

    @Test void missingManifestFails() throws Exception {
        Path jar = bareJar("missing.jar", null, null);
        assertTrue(assertThrows(PluginValidationException.class, () -> new PluginManifestReader().read(jar)).getMessage().contains("was not found"));
    }

    @Test void invalidYamlFails() throws Exception {
        Path jar = bareJar("bad-yaml.jar", "plugin: [unterminated", null);
        assertThrows(PluginValidationException.class, () -> new PluginManifestReader().read(jar));
    }

    @Test void missingEntrypointFails() throws Exception {
        Path jar = pluginJar("absent.jar", "missing.Plugin", "absent", "File", "TextDocument", null, manifest("absent", "missing.Plugin", "File", "TextDocument"));
        try (PluginLoader loader = new PluginLoader()) { assertThrows(PluginValidationException.class, () -> loader.load(jar)); }
    }

    @Test void nonPluginEntrypointFails() throws Exception {
        Path jar = pluginJar("not-plugin.jar", NonPlugin.class.getName(), "not-plugin", "File", "TextDocument", NonPlugin.class, manifest("not-plugin", NonPlugin.class.getName(), "File", "TextDocument"));
        try (PluginLoader loader = new PluginLoader()) { assertTrue(assertThrows(PluginValidationException.class, () -> loader.load(jar)).getMessage().contains("does not implement Plugin")); }
    }

    @Test void constructorFailureFails() throws Exception {
        Path jar = pluginJar("constructor.jar", BrokenConstructorPlugin.class.getName(), "constructor", "File", "TextDocument", BrokenConstructorPlugin.class, manifest("constructor", BrokenConstructorPlugin.class.getName(), "File", "TextDocument"));
        try (PluginLoader loader = new PluginLoader()) { assertThrows(PluginValidationException.class, () -> loader.load(jar)); }
    }

    @Test void runtimeManifestMismatchFails() throws Exception {
        Path jar = pluginJar("mismatch.jar", MismatchPlugin.class.getName(), "expected", "File", "TextDocument", MismatchPlugin.class, manifest("expected", MismatchPlugin.class.getName(), "File", "TextDocument"));
        try (PluginLoader loader = new PluginLoader()) { assertTrue(assertThrows(PluginValidationException.class, () -> loader.load(jar)).getMessage().contains("does not match runtime")); }
    }

    @Test void compatibleChainPasses() {
        assertDoesNotThrow(() -> new PipelineValidator().validate(List.of(plugin("a", "File", "TextDocument"), plugin("b", "TextDocument", "SegmentCollection"), plugin("c", "SegmentCollection", "EmbeddingCollection"), plugin("d", "EmbeddingCollection", "StoredResult"))));
    }

    @Test void incompatibleChainFailsBeforeExecution() {
        assertTrue(assertThrows(PluginValidationException.class, () -> new PipelineValidator().validate(List.of(plugin("extract-text", "File", "TextDocument"), plugin("image-processor", "Image", "ImageResult")))).getMessage().contains("Expected input: Image"));
    }

    @Test void duplicateIdsFail() {
        assertTrue(assertThrows(PluginValidationException.class, () -> new PipelineValidator().validate(List.of(plugin("same", "A", "B"), plugin("same", "B", "C")))).getMessage().contains("duplicate plugin ID"));
    }

    @Test void preflightRunsOnlyAfterAllTypeContractsPass() {
        int[] calls = {0};
        PreflightPlugin first = new PreflightPlugin() {
            private final PluginManifest manifest = new PluginManifest("first", "first", "1", "1.0", "first", "File", "Text");
            public PluginManifest manifest() { return manifest; }
            public void preflight() { calls[0]++; }
            public Object execute(Object value) { fail("must not execute during validation"); return value; }
        };
        Plugin wrongNext = plugin("second", "Image", "Output");
        assertThrows(PluginValidationException.class, () -> new PipelineValidator().validate(List.of(first, wrongNext)));
        assertEquals(0, calls[0]);
    }

    @Test void preflightFailureIsReportedAsValidationFailure() {
        PreflightPlugin plugin = new PreflightPlugin() {
            private final PluginManifest manifest = new PluginManifest("external", "external", "1", "1.0", "external", "A", "B");
            public PluginManifest manifest() { return manifest; }
            public void preflight() { throw new IllegalStateException("service unavailable"); }
            public Object execute(Object value) { fail("must not execute"); return value; }
        };
        assertTrue(assertThrows(PluginValidationException.class, () -> new PipelineValidator().validate(List.of(plugin)))
                .getMessage().contains("service unavailable"));
    }

    @Test void preflightSharesEmbeddingDimensionsWithLaterPlugins() {
        int[] received = {0};
        PreflightPlugin embedder = new PreflightPlugin() {
            private final PluginManifest manifest = new PluginManifest("embed", "embed", "1", "1.0", "embed", "Chunks", "Vectors");
            public PluginManifest manifest() { return manifest; }
            public void preflight() { fail("context-aware preflight should be used"); }
            public void preflight(PreflightContext context) { context.put(PreflightContext.EMBEDDING_DIMENSIONS, 768); }
            public Object execute(Object value) { return value; }
        };
        PreflightPlugin writer = new PreflightPlugin() {
            private final PluginManifest manifest = new PluginManifest("writer", "writer", "1", "1.0", "writer", "Vectors", "Stored");
            public PluginManifest manifest() { return manifest; }
            public void preflight() { fail("context-aware preflight should be used"); }
            public void preflight(PreflightContext context) { received[0] = context.get(PreflightContext.EMBEDDING_DIMENSIONS, Integer.class); }
            public Object execute(Object value) { return value; }
        };
        new PipelineValidator().validate(List.of(embedder, writer));
        assertEquals(768, received[0]);
    }

    private Plugin plugin(String id, String input, String output) {
        PluginManifest m = new PluginManifest(id, id, "1.0.0", "1.0", "test", input, output);
        return new Plugin() { public PluginManifest manifest() { return m; } public Object execute(Object value) { return value; } };
    }

    private Path pluginJar(String name, String entrypoint, String id, String input, String output, Class<?> clazz, String yaml) throws Exception {
        return bareJar(name, yaml, clazz);
    }

    private Path bareJar(String name, String yaml, Class<?> clazz) throws Exception {
        Path jar = temp.resolve(name);
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            if (yaml != null) { out.putNextEntry(new JarEntry(PluginManifestReader.MANIFEST_PATH)); out.write(yaml.getBytes(java.nio.charset.StandardCharsets.UTF_8)); out.closeEntry(); }
            if (clazz != null) addClass(out, clazz);
        }
        return jar;
    }

    private static void addClass(JarOutputStream out, Class<?> type) throws Exception {
        String resource = type.getName().replace('.', '/') + ".class";
        out.putNextEntry(new JarEntry(resource));
        try (InputStream in = type.getClassLoader().getResourceAsStream(resource)) { assertNotNull(in); in.transferTo(out); }
        out.closeEntry();
    }

    private static String manifest(String id, String entrypoint, String input, String output) {
        return "plugin:\n  id: " + id + "\n  name: " + id + "\n  version: 1.0.0\n  apiVersion: \"1.0\"\n  entrypoint: " + entrypoint + "\n  input:\n    type: " + input + "\n  output:\n    type: " + output + "\n";
    }

    public static class GoodPlugin implements Plugin {
        private static final PluginManifest M = new PluginManifest("good", "good", "1.0.0", "1.0", GoodPlugin.class.getName(), "File", "TextDocument");
        public GoodPlugin() {} public PluginManifest manifest() { return M; } public Object execute(Object input) { return input; }
    }
    public static class MismatchPlugin implements Plugin {
        private static final PluginManifest M = new PluginManifest("different", "different", "1.0.0", "1.0", MismatchPlugin.class.getName(), "File", "TextDocument");
        public MismatchPlugin() {} public PluginManifest manifest() { return M; } public Object execute(Object input) { return input; }
    }
    public static class BrokenConstructorPlugin implements Plugin {
        public BrokenConstructorPlugin() { throw new IllegalStateException("constructor failed"); }
        public PluginManifest manifest() { return null; } public Object execute(Object input) { return input; }
    }
    public static class NonPlugin { public NonPlugin() {} }
}
