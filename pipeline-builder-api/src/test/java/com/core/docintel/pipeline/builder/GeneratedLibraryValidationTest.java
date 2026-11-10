package com.core.docintel.pipeline.builder;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.List;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;

class GeneratedLibraryValidationTest {
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
