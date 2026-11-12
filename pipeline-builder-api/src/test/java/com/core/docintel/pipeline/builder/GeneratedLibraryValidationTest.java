package com.core.docintel.pipeline.builder;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.List;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;

class GeneratedLibraryValidationTest {
    static final class RequiresNewApi { com.core.docintel.pipeline.api.ExtractedGraph graph; }
    static final class EmptyClass {}
    private Path classLibrary(String jarName,String entry,Class<?> source)throws Exception{
        Path path=temporary.resolve(jarName+".jar");
        try(var zip=new ZipOutputStream(Files.newOutputStream(path));var input=source.getResourceAsStream("/"+source.getName().replace('.','/')+".class")){
            zip.putNextEntry(new ZipEntry(entry));input.transferTo(zip);zip.closeEntry();
        }
        return path;
    }
    @Test void rejectsApiReferencedOnlyInsidePluginImplementation()throws Exception{
        var plugin=classLibrary("plugin","example/Plugin.class",RequiresNewApi.class);
        var failure=assertThrows(IllegalArgumentException.class,()->GeneratedLibraryValidation.validateApi(List.of(plugin)));
        assertTrue(failure.getMessage().contains("com.core.docintel.pipeline.api.ExtractedGraph"));
        var runtime=classLibrary("runtime","com/core/docintel/pipeline/api/ExtractedGraph.class",EmptyClass.class);
        assertDoesNotThrow(()->GeneratedLibraryValidation.validateApi(List.of(plugin,runtime)));
    }
    @TempDir Path temporary;
    private Path library(String name, String version) throws Exception {
        Path path=temporary.resolve(name+".jar");
        try(var zip=new ZipOutputStream(Files.newOutputStream(path))) {
            zip.putNextEntry(new ZipEntry("META-INF/maven/io.netty/netty-common/pom.properties"));
            zip.write(("artifactId=netty-common\nversion="+version+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return path;
    }
    @Test void rejectsMixedShadedNettyBeforeNativeCompilation() throws Exception {
        var libraries=List.of(library("host","4.2.5.Final"),library("provider","4.1.119.Final"));
        var failure=assertThrows(IllegalArgumentException.class,()->GeneratedLibraryValidation.validateNetty(libraries));
        assertTrue(failure.getMessage().contains("Rebuild the complete PlugIntell reactor"));
    }
    @Test void acceptsMatchingShadedDependencies() throws Exception {
        GeneratedLibraryValidation.validateNetty(List.of(library("host","4.2.5.Final"),library("provider","4.2.5.Final")));
    }
}
