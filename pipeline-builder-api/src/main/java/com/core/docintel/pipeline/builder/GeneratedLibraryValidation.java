package com.core.docintel.pipeline.builder;

import java.io.IOException;
import java.io.DataInputStream;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.ZipFile;

/** Shaded plugin dependencies are invisible to Maven's dependency mediation. */
final class GeneratedLibraryValidation {
    private GeneratedLibraryValidation() {}

    /** Inspect method bodies too: constructing a plugin does not resolve all of its API dependencies. */
    static void validateApi(Collection<Path> libraries) throws IOException {
        String prefix="com/core/docintel/pipeline/api/";
        var pattern=java.util.regex.Pattern.compile(prefix+"[A-Za-z0-9_$]+");
        Set<String> available=new HashSet<>();Map<String,String> required=new TreeMap<>();
        for(Path library:libraries)try(var zip=new ZipFile(library.toFile())){
            var entries=zip.entries();
            while(entries.hasMoreElements()){
                var entry=entries.nextElement();String name=entry.getName();
                if(!name.endsWith(".class")||name.startsWith("META-INF/"))continue;
                if(name.startsWith(prefix))available.add(name.substring(0,name.length()-6));
                try(var input=new DataInputStream(zip.getInputStream(entry))){
                    if(input.readInt()!=0xCAFEBABE)throw new IOException("Invalid class in "+library.getFileName());
                    input.readUnsignedShort();input.readUnsignedShort();int count=input.readUnsignedShort();
                    for(int i=1;i<count;i++)switch(input.readUnsignedByte()){
                        case 1->{var matcher=pattern.matcher(input.readUTF());while(matcher.find())required.putIfAbsent(matcher.group(),library.getFileName().toString());}
                        case 3,4,9,10,11,12,17,18->input.skipNBytes(4);
                        case 5,6->{input.skipNBytes(8);i++;}
                        case 7,8,16,19,20->input.skipNBytes(2);
                        case 15->input.skipNBytes(3);
                        default->throw new IOException("Unknown class constant in "+library.getFileName());
                    }
                }
            }
        }
        for(var dependency:required.entrySet())if(!available.contains(dependency.getKey()))
            throw new IllegalArgumentException("Generated runtime is missing API class "+dependency.getKey().replace('/','.')+
                " required by "+dependency.getValue()+". Rebuild the complete PlugIntell reactor and regenerate the project; plugin and pipeline-core JARs must use the same API.");
    }

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
