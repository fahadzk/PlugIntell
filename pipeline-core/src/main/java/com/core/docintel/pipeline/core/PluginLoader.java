package com.core.docintel.pipeline.core;

import com.core.docintel.pipeline.api.Plugin;
import com.core.docintel.pipeline.api.PluginManifest;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class PluginLoader implements AutoCloseable {
    private record ApplicationEntry(URLClassLoader loader,com.core.docintel.pipeline.api.ApplicationScopedPlugin owner,long modified,long size){}
    private static final java.util.Map<Path,ApplicationEntry> applicationLoaders=new java.util.HashMap<>();
    static {Runtime.getRuntime().addShutdownHook(new Thread(PluginLoader::closeApplicationPlugins,"application-plugins-shutdown"));}
    /** Host shutdown hook and explicit embedding lifecycle; do not call between queries. */
    public static void closeApplicationPlugins(){synchronized(applicationLoaders){
        for(var entry:applicationLoaders.values()){try{entry.owner().closeApplication();}catch(RuntimeException ignored){}try{entry.loader().close();}catch(java.io.IOException ignored){}}
        applicationLoaders.clear();
    }}
    private final PluginManifestReader manifestReader = new PluginManifestReader();
    private final List<URLClassLoader> classLoaders = new ArrayList<>();

    public Plugin load(Path jar) {
        return load(jar,com.core.docintel.pipeline.api.PluginMessages.noop());
    }
    public synchronized Plugin load(Path jar,com.core.docintel.pipeline.api.PluginMessages messages) {
        PluginManifest declared = manifestReader.read(jar);
        try {synchronized(applicationLoaders){
            if (!Files.isRegularFile(jar)) throw new PluginValidationException("Plugin JAR does not exist: " + jar);
            Path key=jar.toRealPath();var cached=applicationLoaders.get(key);long modified=Files.getLastModifiedTime(key).toMillis(),size=Files.size(key);
            if(cached!=null&&(cached.modified()!=modified||cached.size()!=size))throw new PluginValidationException("Application-scoped plugin was rebuilt. Restart the host to load its new version: "+jar.getFileName());
            URLClassLoader classLoader = cached==null?new PluginClassLoader(key.toUri().toURL(), Plugin.class.getClassLoader()):cached.loader();
            if(cached==null)classLoaders.add(classLoader);
            Class<?> entrypoint = Class.forName(declared.entrypoint(), true, classLoader);
            if (!Plugin.class.isAssignableFrom(entrypoint)) {
                throw new PluginValidationException("Invalid plugin JAR:\n\n" + jar.getFileName() + "\n\nReason:\nEntrypoint " + declared.entrypoint() + " does not implement Plugin.");
            }
            Plugin instance;
            try{instance=(Plugin)entrypoint.getConstructor(com.core.docintel.pipeline.api.PluginMessages.class).newInstance(messages);}
            catch(NoSuchMethodException legacy){instance=(Plugin)entrypoint.getDeclaredConstructor().newInstance();}
            PluginManifest runtime = instance.manifest();
            if (runtime == null) throw new PluginValidationException("Plugin runtime manifest is null.");
            if (!declared.equals(runtime)) {
                throw new PluginValidationException("Plugin manifest does not match runtime implementation.\nJAR manifest: " + declared + "\nRuntime plugin: " + runtime);
            }
            if(instance instanceof com.core.docintel.pipeline.api.ApplicationScopedPlugin owner){applicationLoaders.putIfAbsent(key,new ApplicationEntry(classLoader,owner,modified,size));classLoaders.remove(classLoader);}
            return instance;
        }} catch (PluginValidationException e) {
            throw e;
        } catch (Throwable e) {
            throw new PluginValidationException("Invalid plugin JAR:\n\n" + jar.getFileName() + "\n\nReason:\nCould not load or instantiate entrypoint " + declared.entrypoint() + ": " + rootMessage(e), e);
        }
    }

    /** Studio's existing reflective compatibility boundary; generated applications never call this. */
    public Plugin reportingAttempt(Plugin previous,com.core.docintel.pipeline.api.PluginMessages messages){
        try{return (Plugin)previous.getClass().getConstructor(com.core.docintel.pipeline.api.PluginMessages.class).newInstance(messages);}
        catch(NoSuchMethodException legacy){return previous;}
        catch(ReflectiveOperationException failure){throw new PluginValidationException("Could not construct reporting attempt.",failure);}
    }
    public boolean supportsReporting(Plugin plugin){try{plugin.getClass().getConstructor(com.core.docintel.pipeline.api.PluginMessages.class);return true;}catch(NoSuchMethodException legacy){return false;}}

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
