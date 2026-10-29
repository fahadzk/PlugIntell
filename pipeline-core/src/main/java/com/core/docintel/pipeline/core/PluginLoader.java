package com.core.docintel.pipeline.core;

import com.core.docintel.pipeline.api.Plugin;
import com.core.docintel.pipeline.api.PluginManifest;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class PluginLoader implements AutoCloseable {
    private final PluginManifestReader manifestReader = new PluginManifestReader();
    private final List<URLClassLoader> classLoaders = new ArrayList<>();

    public Plugin load(Path jar) {
        PluginManifest declared = manifestReader.read(jar);
        try {
            if (!Files.isRegularFile(jar)) throw new PluginValidationException("Plugin JAR does not exist: " + jar);
            URLClassLoader classLoader = new PluginClassLoader(jar.toAbsolutePath().toUri().toURL(), Plugin.class.getClassLoader());
            classLoaders.add(classLoader);
            Class<?> entrypoint = Class.forName(declared.entrypoint(), true, classLoader);
            if (!Plugin.class.isAssignableFrom(entrypoint)) {
                throw new PluginValidationException("Invalid plugin JAR:\n\n" + jar.getFileName() + "\n\nReason:\nEntrypoint " + declared.entrypoint() + " does not implement Plugin.");
            }
            Plugin instance = (Plugin) entrypoint.getDeclaredConstructor().newInstance();
            PluginManifest runtime = instance.manifest();
            if (runtime == null) throw new PluginValidationException("Plugin runtime manifest is null.");
            if (!declared.equals(runtime)) {
                throw new PluginValidationException("Plugin manifest does not match runtime implementation.\nJAR manifest: " + declared + "\nRuntime plugin: " + runtime);
            }
            return instance;
        } catch (PluginValidationException e) {
            throw e;
        } catch (Throwable e) {
            throw new PluginValidationException("Invalid plugin JAR:\n\n" + jar.getFileName() + "\n\nReason:\nCould not load or instantiate entrypoint " + declared.entrypoint() + ": " + rootMessage(e), e);
        }
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    /** Plugin implementation classes are private to this JAR; the shared API always comes from core. */
    private static final class PluginClassLoader extends URLClassLoader {
        PluginClassLoader(java.net.URL jar, ClassLoader parent) { super(new java.net.URL[]{jar}, parent); }
        @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null && !name.startsWith("java.") && !name.startsWith("javax.")
                        && !name.startsWith("jdk.") && !name.startsWith("com.core.docintel.pipeline.api.")) {
                    try { loaded = findClass(name); } catch (ClassNotFoundException ignored) { }
                }
                if (loaded == null) loaded = super.loadClass(name, false);
                if (resolve) resolveClass(loaded);
                return loaded;
            }
        }
    }

    @Override public void close() {
        for (URLClassLoader loader : classLoaders) {
            try { loader.close(); } catch (java.io.IOException ignored) { }
        }
    }
}
