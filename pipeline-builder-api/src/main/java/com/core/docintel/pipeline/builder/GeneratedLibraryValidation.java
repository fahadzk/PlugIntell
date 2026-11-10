package com.core.docintel.pipeline.builder;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.ZipFile;

/** Shaded plugin dependencies are invisible to Maven's dependency mediation. */
final class GeneratedLibraryValidation {
    private GeneratedLibraryValidation() {}

    static void validateNetty(Collection<Path> libraries) throws IOException {
        Map<String, String> versions = new HashMap<>();
        for (Path library : libraries) {
            try (var zip = new ZipFile(library.toFile())) {
                var entries = zip.entries();
                while (entries.hasMoreElements()) {
                    var entry = entries.nextElement();
                    if (!entry.getName().startsWith("META-INF/maven/io.netty/") ||
                        !entry.getName().endsWith("/pom.properties")) continue;
                    Properties properties = new Properties();
                    try (var input = zip.getInputStream(entry)) { properties.load(input); }
                    String artifact = properties.getProperty("artifactId");
                    String version = properties.getProperty("version");
                    if (artifact == null || version == null) continue;
                    String previous = versions.putIfAbsent(artifact, version);
                    if (previous != null && !previous.equals(version)) {
                        throw new IllegalArgumentException("Selected libraries contain incompatible Netty versions for " +
                            artifact + ": " + previous + " and " + version +
                            ". Rebuild the complete PlugIntell reactor and regenerate the project; " +
                            "shaded plugin dependencies cannot be corrected by changing the generated POM.");
                    }
                }
            }
        }
    }
}
