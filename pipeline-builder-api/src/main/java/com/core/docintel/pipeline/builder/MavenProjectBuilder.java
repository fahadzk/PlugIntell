package com.core.docintel.pipeline.builder;

import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import java.util.function.Consumer;
import java.io.RandomAccessFile;

/** Runs Maven only to package the generated application; pipeline plugins still execute in-process. */
@Singleton
public final class MavenProjectBuilder {
    private final String mavenHome;
    private final String configuredRepository;
    private final long timeoutSeconds;

    public MavenProjectBuilder(@Value("${pipeline.builder.maven-home:}") String mavenHome,
                               @Value("${pipeline.builder.maven-repo-local:}") String repository,
                               @Value("${pipeline.builder.maven-timeout-seconds:3600}") long timeoutSeconds) {
        this.mavenHome = mavenHome == null ? "" : mavenHome.trim();
        this.configuredRepository = repository == null || repository.isBlank()
                ? findRepositorySetting(Path.of("").toAbsolutePath()) : repository.trim();
        if (timeoutSeconds < 60) throw new IllegalArgumentException("Maven build timeout must be at least 60 seconds.");
        this.timeoutSeconds = timeoutSeconds;
    }

    public Path build(Path project, String outputFormat, String projectName) {
        return build(project, outputFormat, projectName, ignored -> {});
    }

    public Path build(Path project, String outputFormat, String projectName, Consumer<String> output) {
        Path log = project.resolve("target/maven-build.log");
        try {
            Files.createDirectories(log.getParent());
            List<String> args = new ArrayList<>();
            args.add("-B");
            args.add("-DskipTests");
            if (!configuredRepository.isBlank()) args.add("-Dmaven.repo.local=" + configuredRepository);
            if ("native-image".equals(outputFormat)) args.add("-Pnative");
            args.add("package");

            ProcessBuilder processBuilder = new ProcessBuilder(command(args));
            processBuilder.directory(project.toFile());
            processBuilder.redirectErrorStream(true);
            processBuilder.redirectOutput(log.toFile());
            Process process = processBuilder.start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
            long position = 0;
            boolean completed = false;
            while (System.nanoTime() < deadline) {
                if (Files.exists(log)) {
                    try (RandomAccessFile reader = new RandomAccessFile(log.toFile(), "r")) {
                        reader.seek(position);
                        String line;
                        while ((line = reader.readLine()) != null) output.accept(new String(line.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1), java.nio.charset.StandardCharsets.UTF_8));
                        position = reader.getFilePointer();
                    }
                }
                if (process.waitFor(500, TimeUnit.MILLISECONDS)) { completed = true; break; }
            }
            if (Files.exists(log)) {
                try (RandomAccessFile reader = new RandomAccessFile(log.toFile(), "r")) {
                    reader.seek(position); String line;
                    while ((line = reader.readLine()) != null) output.accept(new String(line.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1), java.nio.charset.StandardCharsets.UTF_8));
                }
            }
            if (!completed) {
                process.destroyForcibly();
                process.waitFor(10, TimeUnit.SECONDS);
                throw new IllegalStateException("Maven build timed out after " + Duration.ofSeconds(timeoutSeconds) + ".\n" + tail(log));
            }
            if (process.exitValue() != 0) {
                throw new IllegalStateException("Maven build failed with exit code " + process.exitValue() + ".\n" + tail(log));
            }
            Path target = project.resolve("target");
            if ("native-image".equals(outputFormat)) {
                Path nativeArtifact = target.resolve(projectName);
                if (Files.isRegularFile(nativeArtifact)) return nativeArtifact;
                Path windowsArtifact = target.resolve(projectName + ".exe");
                if (Files.isRegularFile(windowsArtifact)) return windowsArtifact;
                throw new IllegalStateException("Maven completed but the native executable was not created.\n" + tail(log));
            }
            Path jar = target.resolve(projectName.toLowerCase(Locale.ROOT) + "-1.0.0.jar");
            if (!Files.isRegularFile(jar)) throw new IllegalStateException("Maven completed but the runnable JAR was not created.\n" + tail(log));
            return jar;
        } catch (IOException e) {
            throw new IllegalStateException("Could not start Maven. Set MAVEN_HOME or add Maven to PATH. " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Maven build was interrupted.", e);
        }
    }

    private List<String> command(List<String> args) {
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        Path executable = mavenHome.isBlank() ? null : Path.of(mavenHome).resolve("bin").resolve(windows ? "mvn.cmd" : "mvn");
        if (executable != null && !Files.isRegularFile(executable)) {
            throw new IllegalStateException("Maven executable was not found: " + executable + ". Check MAVEN_HOME.");
        }
        if (!windows) {
            List<String> command = new ArrayList<>();
            command.add(executable == null ? "mvn" : executable.toString());
            command.addAll(args);
            return command;
        }
        String executableName = executable == null ? "mvn.cmd" : executable.toString();
        StringBuilder shell = new StringBuilder(executable == null ? executableName : quote(executableName));
        for (String arg : args) {
            shell.append(' ');
            shell.append(arg.contains(" ") ? quote(arg) : arg);
        }
        return List.of("cmd.exe", "/d", "/s", "/c", "\"" + shell + "\"");
    }

    private static String quote(String value) {
        return "\"" + value.replace("\"", "\\\"") + "\"";
    }

    private static String findRepositorySetting(Path start) {
        for (Path current = start; current != null; current = current.getParent()) {
            Path config = current.resolve(".mvn/maven.config");
            if (!Files.isRegularFile(config)) continue;
            try (Stream<String> lines = Files.lines(config)) {
                String value = lines.map(String::trim).filter(line -> line.startsWith("-Dmaven.repo.local="))
                        .map(line -> line.substring("-Dmaven.repo.local=".length()).trim()).findFirst().orElse("");
                if (!value.isBlank()) return value.replace("\\:", ":");
            } catch (IOException ignored) { return ""; }
        }
        return "";
    }

    private static String tail(Path file) {
        try {
            List<String> lines = Files.readAllLines(file);
            int start = Math.max(0, lines.size() - 80);
            return String.join(System.lineSeparator(), lines.subList(start, lines.size()));
        } catch (IOException e) {
            return "Maven output log is unavailable: " + e.getMessage();
        }
    }
}
