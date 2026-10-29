package com.core.docintel.pipeline.core;

import com.core.docintel.pipeline.api.AnswerResult;
import com.core.docintel.pipeline.api.Plugin;
import com.core.docintel.pipeline.api.SearchRequest;
import com.core.docintel.pipeline.api.StoredResult;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;
import java.util.function.Supplier;

/** CLI for the PDF ingestion and interactive RAG retrieval flows. */
public final class RagPipelineMain {
    private RagPipelineMain() {}

    public static void main(String[] args) {
        if (args.length == 0) {
            usage();
            System.exit(2);
        }
        try {
            switch (args[0]) {
                case "ingest" -> ingest(args);
                case "query" -> query(args);
                default -> { usage(); System.exit(2); }
            }
        } catch (Exception e) {
            System.err.println("Pipeline failed: " + e.getMessage());
            System.exit(1);
        }
    }

    private static void ingest(String[] args) {
        if (args.length != 6) throw new IllegalArgumentException("Usage: ingest <pdf> <pdf-ocr.jar> <splitter.jar> <embedding.jar> <neo4j-writer.jar>");
        run(List.of(Path.of(args[2]), Path.of(args[3]), Path.of(args[4]), Path.of(args[5])),
                () -> Path.of(args[1]).toFile());
    }

    private static void query(String[] args) {
        boolean citations = true;
        int topK = 5;
        List<String> jarArgs = new ArrayList<>();
        for (int i = 1; i < args.length; i++) {
            if (args[i].equals("--no-citations")) citations = false;
            else if (args[i].startsWith("--top-k=")) topK = Integer.parseInt(args[i].substring("--top-k=".length()));
            else if (args[i].startsWith("--")) throw new IllegalArgumentException("Unknown option: " + args[i]);
            else jarArgs.add(args[i]);
        }
        if (jarArgs.size() != 2) throw new IllegalArgumentException("Usage: query [--no-citations] [--top-k=N] <retrieval.jar> <answer.jar>");
        if (topK < 1 || topK > 100) throw new IllegalArgumentException("--top-k must be between 1 and 100.");
        boolean includeCitations = citations;
        int requestedTopK = topK;
        run(List.of(Path.of(jarArgs.get(0)), Path.of(jarArgs.get(1))),
                () -> promptSearchRequest(includeCitations, requestedTopK));
    }

    private static SearchRequest promptSearchRequest(boolean citations, int topK) {
        try (Scanner scanner = new Scanner(System.in)) {
            System.out.print("Search query: ");
            if (!scanner.hasNextLine()) throw new IllegalArgumentException("No search query was entered.");
            String question = scanner.nextLine().trim();
            if (question.isEmpty()) throw new IllegalArgumentException("Search query must not be blank.");
            return new SearchRequest(question, citations, topK);
        }
    }

    private static void run(List<Path> jars, Supplier<Object> inputSupplier) {
        try (PluginLoader loader = new PluginLoader()) {
            List<Plugin> plugins = new ArrayList<>();
            for (Path jar : jars) plugins.add(loader.load(jar));
            try (Pipeline pipeline = new Pipeline(plugins)) {
                System.out.println("Validating pipeline and checking configured services...");
                new PipelineValidator().validate(plugins);
                System.out.println("\nPipeline is valid.\n\nExecuting...");
                Object result = pipeline.execute(inputSupplier.get());
                if (result instanceof StoredResult stored) {
                    System.out.println("Stored " + stored.storedChunks() + " embeddings.");
                } else if (result instanceof AnswerResult answer) {
                    System.out.println("\nAnswer:\n" + answer.answer());
                    if (answer.citationsEnabled()) {
                        System.out.println("\nSources:");
                        for (int i = 0; i < answer.citations().size(); i++) {
                            var source = answer.citations().get(i);
                            System.out.printf("[%d] %s, page %d (score %.4f)%n", i + 1, source.sourceName(), source.pageNumber(), source.score());
                        }
                    }
                }
                System.out.println("\nPipeline completed successfully.");
            }
        }
    }

    private static void usage() {
        System.err.println("Usage:\n"
                + "  java -cp pipeline-core.jar com.core.docintel.pipeline.core.RagPipelineMain ingest <pdf> <pdf-ocr.jar> <splitter.jar> <embedding.jar> <neo4j-writer.jar>\n"
                + "  java -cp pipeline-core.jar com.core.docintel.pipeline.core.RagPipelineMain query [--no-citations] [--top-k=N] <retrieval.jar> <answer.jar>");
    }
}
