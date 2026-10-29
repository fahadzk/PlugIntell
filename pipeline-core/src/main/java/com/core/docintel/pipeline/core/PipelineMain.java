package com.core.docintel.pipeline.core;

import com.core.docintel.pipeline.api.Plugin;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class PipelineMain {
    private PipelineMain() {}
    public static void main(String[] args) {
        if (args.length == 0) {
            System.err.println("Usage:\n\njava -jar pipeline-core.jar <plugin1.jar> <plugin2.jar> ...");
            System.exit(1);
        }
        try (PluginLoader loader = new PluginLoader()) {

            List<Plugin> plugins = new ArrayList<>();
            System.out.println("Loading plugins...");

            for (int i = 0; i < args.length; i++) {
                Plugin plugin = loader.load(Path.of(args[i]));
                plugins.add(plugin);
                var m = plugin.manifest();
                System.out.printf("%n[%d] %s%n    %s -> %s%n    VALID%n", i + 1, m.id(), m.inputType(), m.outputType());
            }

            System.out.println("\nValidating pipeline...\n");

            Pipeline pipeline = new Pipeline(plugins);
            try (pipeline) {
            new PipelineValidator().validate(plugins);
            for (int i = 1; i < plugins.size(); i++) {
                var before = plugins.get(i - 1).manifest(); var after = plugins.get(i).manifest();
                System.out.printf("%-20s -> %-20s OK%n", before.id(), after.id());
            }


            System.out.println("\nPipeline is valid.\n\nExecuting...");
            pipeline.execute(Path.of("sample.txt").toFile());
            }
            System.out.println("\nPipeline completed successfully.");

        } catch (Exception e) {
            System.err.println(e.getMessage());
            System.exit(1);
        }
    }
}
