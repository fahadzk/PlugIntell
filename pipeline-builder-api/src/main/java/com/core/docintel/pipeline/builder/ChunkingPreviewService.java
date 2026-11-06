package com.core.docintel.pipeline.builder;

import com.core.docintel.pipeline.api.*;
import com.core.docintel.pipeline.core.PluginLoader;
import jakarta.inject.Singleton;
import java.util.*;

/** Executes a text-document chunker in memory for preview; it never writes ingestion data. */
@Singleton
public final class ChunkingPreviewService {
    private static final int MAX_PREVIEW_CHARACTERS = 100_000;
    private final PluginCatalog catalog;
    private final GraphPipelineValidator validator;

    public ChunkingPreviewService(PluginCatalog catalog,GraphPipelineValidator validator) { this.catalog = catalog;this.validator=validator; }

    public ChunkingPreviewResult preview(ChunkingPreviewRequest request) {
        if (request == null || request.pluginId() == null || request.pluginId().isBlank())
            throw new IllegalArgumentException("pluginId is required.");
        String text = Objects.requireNonNullElse(request.text(), "");
        if (text.isBlank()) throw new IllegalArgumentException("Preview text is required.");
        if (text.length() > MAX_PREVIEW_CHARACTERS)
            throw new IllegalArgumentException("Preview text exceeds the " + MAX_PREVIEW_CHARACTERS + " character limit.");
        PluginInfo info = catalog.all().stream().filter(p -> p.id().equals(request.pluginId())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown plugin ID: " + request.pluginId()));
        if (request.pluginVersion() != null && !request.pluginVersion().equals(info.version()))
            throw new IllegalArgumentException("Requested plugin version is not installed: " + request.pluginVersion());
        if (!"TextDocument".equals(info.inputType()) || !"ChunkCollection".equals(info.outputType()))
            throw new IllegalArgumentException("Chunk preview supports installed text-document chunkers only.");
        GraphValidation configValidation=validator.validatePluginConfiguration(info.id(),info.version(),request.config(),catalog.all(),false);
        if(!configValidation.valid())throw new IllegalArgumentException(String.join("; ",configValidation.errors()));
        try (PluginLoader loader = new PluginLoader()) {
            Plugin plugin = loader.load(catalog.pluginJar(info));
            if (request.config() != null && !request.config().isEmpty()) {
                if (!(plugin instanceof ConfigurablePlugin configurable))
                    throw new IllegalArgumentException("Plugin does not accept configuration.");
                configurable.configure(request.config());
            }
            Object output = plugin.execute(new TextDocument("preview", "preview.txt", List.of(new TextPage(1, text))));
            List<String> values;
            if (output instanceof ChunkCollection chunks) values = chunks.chunks().stream().map(TextChunk::text).toList();
            else throw new IllegalStateException("Chunker returned an unsupported preview output.");
            List<ChunkingPreviewResult.ChunkPreview> previews = new ArrayList<>(values.size());
            for (int i = 0; i < values.size(); i++) previews.add(new ChunkingPreviewResult.ChunkPreview(i, values.get(i), values.get(i).length()));
            return new ChunkingPreviewResult(info.id(), text.length(), previews.size(), List.copyOf(previews));
        }
    }
}
