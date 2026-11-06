package com.core.docintel.pipeline.builder;

import jakarta.inject.Singleton;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

@Singleton
public final class PipelineProjectGenerator {
    private final PipelineService service;
    private final PluginCatalog catalog;
    private final MavenProjectBuilder mavenBuilder;
    public PipelineProjectGenerator(PipelineService service, PluginCatalog catalog, MavenProjectBuilder mavenBuilder) { this.service=service; this.catalog=catalog; this.mavenBuilder=mavenBuilder; }

    public byte[] generate(PipelineRequest request) {
        PipelineValidation validation=service.validate(request);
        if(!validation.valid() || !validation.generationSupported()) throw new IllegalArgumentException(String.join("\n",validation.errors())+(validation.generationMessage().isBlank()?"":"\n"+validation.generationMessage()));
        String name=safeName(request.projectName());
        Path base=Path.of("target","generated-projects").toAbsolutePath().normalize();
        Path dir=base.resolve(UUID.randomUUID().toString()).resolve(name).normalize();
        if(!dir.startsWith(base))throw new IllegalArgumentException("Invalid project path.");
        try {
            createProject(request,dir);
            Path builtArtifact = mavenBuilder.build(dir, request.outputFormat() == null ? "jar" : request.outputFormat(), name);
            return zip(dir,name,builtArtifact);
        } catch(IOException e){throw new IllegalStateException("Could not generate pipeline project: "+e.getMessage(),e);}
        finally { try { if(Files.exists(dir)) deleteTree(dir.getParent()); } catch(IOException ignored){} }
    }

    /** Creates a durable generated project in an empty directory without starting a build. */
    public Path createProject(PipelineRequest request, Path destination) {
        PipelineValidation validation=service.validate(request);
        if(!validation.valid() || !validation.generationSupported()) throw new IllegalArgumentException(String.join("\n",validation.errors())+(validation.generationMessage().isBlank()?"":"\n"+validation.generationMessage()));
        String name=safeName(request.projectName());
        Path dir=destination.toAbsolutePath().normalize();
        try {
            if (Files.exists(dir)) {
                if (!Files.isDirectory(dir)) throw new IllegalArgumentException("Project location must be a directory.");
                if (Files.isSymbolicLink(dir)) throw new IllegalArgumentException("Project location cannot be a symbolic link.");
                try (var entries=Files.list(dir)) {
                    if(entries.findAny().isPresent())throw new IllegalArgumentException("Project location must be an empty directory.");
                }
            }
            Files.createDirectories(dir.resolve("src/main/java/generated/pipeline"));
            Files.createDirectories(dir.resolve("lib"));
            Files.copy(catalog.pipelineCoreJar(), dir.resolve("lib/pipeline-core.jar"));
            for (PluginInfo p : validation.plugins()) Files.copy(catalog.pluginJar(p), dir.resolve("lib").resolve(p.artifactId()+".jar"));
            write(dir.resolve("pom.xml"),pom(request,validation.plugins()));
            write(dir.resolve("src/main/java/generated/pipeline/GeneratedPipelineMain.java"),javaSource(validation.plugins()));
            write(dir.resolve("README.md"),readme(name,request,validation.plugins()));
            write(dir.resolve(".gitignore"),"target/\n.env\n");
            write(dir.resolve(".env.example"),envExample(validation.plugins()));
            write(dir.resolve("pipeline-env.bat"),envFile(validation.plugins(),request.configuration(),false));
            write(dir.resolve("pipeline-env.ps1"),envFile(validation.plugins(),request.configuration(),true));
            return dir;
        } catch(IOException e){throw new IllegalStateException("Could not create project: "+e.getMessage(),e);}
    }

    private static String pom(PipelineRequest r,List<PluginInfo> ps) {
        StringBuilder b=new StringBuilder("""
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
                  <modelVersion>4.0.0</modelVersion><groupId>generated.pipeline</groupId><artifactId>""").append(xml(safeName(r.projectName()).toLowerCase(Locale.ROOT))).append("</artifactId><version>1.0.0</version>\n")
                .append("<properties><maven.compiler.release>21</maven.compiler.release><project.build.sourceEncoding>UTF-8</project.build.sourceEncoding></properties><dependencies>\n")
                .append(localJarDep("pipeline-core", "pipeline-core.jar"));
        for(PluginInfo p:ps)b.append(localJarDep(p.artifactId(), p.artifactId()+".jar"));
        StringBuilder classPath = new StringBuilder("../lib/pipeline-core.jar");
        for (PluginInfo p : ps) classPath.append(' ').append("../lib/").append(p.artifactId()).append(".jar");
        b.append("</dependencies><build><plugins><plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-compiler-plugin</artifactId><version>3.14.0</version><configuration><release>21</release><proc>none</proc></configuration></plugin><plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-jar-plugin</artifactId><version>3.4.1</version><configuration><archive><manifest><mainClass>generated.pipeline.GeneratedPipelineMain</mainClass></manifest><manifestEntries><Class-Path>").append(classPath).append("</Class-Path></manifestEntries></archive></configuration></plugin></plugins></build>");
        if("native-image".equalsIgnoreCase(r.outputFormat())) b.append("<profiles><profile><id>native</id><build><plugins><plugin><groupId>org.graalvm.buildtools</groupId><artifactId>native-maven-plugin</artifactId><version>0.10.6</version><extensions>true</extensions><configuration><imageName>").append(xml(safeName(r.projectName()))).append("</imageName><buildArgs><buildArg>--no-fallback</buildArg></buildArgs></configuration><executions><execution><goals><goal>compile-no-fork</goal></goals></execution></executions></plugin></plugins></build></profile></profiles>");
        return b.append("</project>").toString();
    }
    private static String localJarDep(String artifact,String file){return "<dependency><groupId>generated.pipeline</groupId><artifactId>"+xml(artifact)+"</artifactId><version>1.0.0</version><scope>system</scope><systemPath>${project.basedir}/lib/"+xml(file)+"</systemPath></dependency>\n";}
    private static String javaSource(List<PluginInfo> ps){
        StringBuilder b=new StringBuilder("package generated.pipeline;\nimport com.core.docintel.pipeline.api.*;\nimport com.core.docintel.pipeline.core.*;\nimport java.io.File;\nimport java.util.*;\nimport java.util.Scanner;\n\npublic final class GeneratedPipelineMain {\n public static void main(String[] args) {\n  List<Plugin> plugins = List.of(\n");
        for(int i=0;i<ps.size();i++) b.append("   new ").append(ps.get(i).entrypoint()).append("()").append(i+1<ps.size()?",\n":"\n");
        b.append("  );\n  try (Pipeline pipeline = new Pipeline(plugins)) {\n   new PipelineValidator().validate(plugins);\n   Object input = ");
        switch(ps.getFirst().inputType()){
            case "File" -> b.append("new File(requireInput(args))");
            case "String" -> b.append("args.length > 0 ? String.join(\" \", args) : \"sample input\"");
            case "SearchRequest" -> b.append("new SearchRequest(prompt(), Boolean.parseBoolean(System.getenv().getOrDefault(\"RAG_SEARCH_INCLUDE_CITATIONS\", \"true\")), Integer.parseInt(System.getenv().getOrDefault(\"RAG_SEARCH_TOP_K\", \"5\")))");
            default -> throw new IllegalArgumentException("Unsupported input type");
        }
        b.append(";\n   Object result = pipeline.execute(input);\n   System.out.println(\"Pipeline completed successfully. Result: \" + result);\n  } catch (Exception e) { System.err.println(\"Pipeline failed: \" + e.getMessage()); System.exit(1); }\n }\n private static String requireInput(String[] args) { if(args.length==0) throw new IllegalArgumentException(\"Usage: java -jar <application.jar> <input-file>\"); return args[0]; }\n private static String prompt() { System.out.print(\"Search query: \" ); return new Scanner(System.in).nextLine(); }\n}\n");
        return b.toString();
    }
    private static String readme(String name, PipelineRequest request, List<PluginInfo> plugins) {
        String artifact = name.toLowerCase(Locale.ROOT) + "-1.0.0.jar";
        StringBuilder readme = new StringBuilder("# ").append(name)
                .append("\n\nGenerated runnable pipeline. Plugin classes are linked directly into the application; execution and validation happen in one JVM. The selected plugin JARs are bundled in `lib/`.\n\n## Build\n\n`mvn clean package`\n");
        if ("File".equals(plugins.getFirst().inputType())) {
            readme.append("\nRun the JAR: `java -jar target/").append(artifact).append(" <input-file>`\n");
        } else {
            readme.append("\nRun the JAR: `java -jar target/").append(artifact).append("`\n");
        }
        if ("native-image".equalsIgnoreCase(request.outputFormat())) {
            readme.append("\nThe generated native executable is in `target/").append(name)
                    .append("` (Linux/macOS) or `target/").append(name).append(".exe` (Windows).\n");
        }
        readme.append("\n## Plugins\n\n");
        for (PluginInfo plugin : plugins) readme.append("- `").append(plugin.id()).append("`: ")
                .append(plugin.inputType()).append(" -> ").append(plugin.outputType()).append("\n");
        readme.append("\n## Configuration\n\nSet local environment values from `pipeline-env.bat` or `pipeline-env.ps1` (`call pipeline-env.bat` in Command Prompt, `. .\\pipeline-env.ps1` in PowerShell). `.env.example` lists the variables. Secret values are placeholders and are never included from the browser request.\n");
        if ("native-image".equalsIgnoreCase(request.outputFormat())) {
            readme.append("\nFor GraalVM native image, build with `mvn -Pnative package`. This requires GraalVM Native Image and a supported native toolchain; plugins that use unsupported dynamic reflection may need reachability metadata.\n");
        }
        return readme.toString();
    }
    private static String envExample(List<PluginInfo> ps){Set<String> keys=new TreeSet<>();ps.forEach(p->p.configuration().forEach(c->keys.add(c.name())));StringBuilder b=new StringBuilder("# Copy values into your local environment; never commit secrets.\n");for(String key:keys){ConfigOption o=ps.stream().flatMap(p->p.configuration().stream()).filter(c->c.name().equals(key)).findFirst().orElseThrow();b.append(key).append('=').append(o.sensitive()?"":o.defaultValue()).append('\n');}return b.toString();}
    private static String envFile(List<PluginInfo> ps,Map<String,String> values,boolean powershell){Set<String> keys=new TreeSet<>();ps.forEach(p->p.configuration().forEach(c->keys.add(c.name())));StringBuilder b=new StringBuilder(powershell?"# Set local values before running.\n":"@echo off\r\nREM Set local values before running.\r\n");for(String key:keys){ConfigOption o=ps.stream().flatMap(p->p.configuration().stream()).filter(c->c.name().equals(key)).findFirst().orElseThrow();String supplied=values==null?null:values.get(key);String value=o.sensitive()?"CHANGE_ME":(supplied==null||supplied.isBlank()?o.defaultValue():supplied);if(value==null)value="";if(value.contains("\r")||value.contains("\n"))throw new IllegalArgumentException("Configuration value contains a newline: "+key);if(powershell)b.append("$env:").append(key).append(" = '").append(value.replace("'","''")).append("'\n");else b.append("set \"").append(key).append("=").append(value.replace("%","%%").replace("\"","")).append("\"\r\n");}return b.toString();}
    private static String safeName(String s){if(s==null||!s.matches("[A-Za-z][A-Za-z0-9_-]{0,63}"))throw new IllegalArgumentException("Project name must start with a letter and contain only letters, digits, '_' or '-'.");return s;}
    private static String xml(String s){return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&apos;");}
    private static void write(Path p,String s)throws IOException{Files.writeString(p,s);}
    private static byte[] zip(Path dir,String name,Path builtArtifact)throws IOException{ByteArrayOutputStream out=new ByteArrayOutputStream();Path target=dir.resolve("target").normalize();if(!builtArtifact.normalize().startsWith(target))throw new IllegalArgumentException("Build artifact is outside the generated project.");try(ZipOutputStream zip=new ZipOutputStream(out);var paths=Files.walk(dir)){for(Path p:paths.filter(Files::isRegularFile).sorted().toList()){if(p.normalize().startsWith(target)&&!p.normalize().equals(builtArtifact.normalize()))continue;String entry=name+"/"+dir.relativize(p).toString().replace('\\','/');zip.putNextEntry(new ZipEntry(entry));Files.copy(p,zip);zip.closeEntry();}}return out.toByteArray();}
    private static void deleteTree(Path root)throws IOException{if(root==null||!Files.exists(root))return;try(var paths=Files.walk(root)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}}
}
