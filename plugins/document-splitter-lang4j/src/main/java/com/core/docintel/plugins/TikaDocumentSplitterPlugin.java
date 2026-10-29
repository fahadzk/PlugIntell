package com.core.docintel.plugins;

import com.core.docintel.pipeline.api.DocumentSplitterPlugin;
import com.core.docintel.pipeline.api.PluginManifest;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentParser;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.data.document.parser.apache.tika.ApacheTikaDocumentParser;
import dev.langchain4j.data.document.splitter.DocumentByCharacterSplitter;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

/** Parses supported document formats with Apache Tika and splits text with LangChain4j. */
public final class TikaDocumentSplitterPlugin implements DocumentSplitterPlugin {

    private static final PluginManifest MANIFEST = new PluginManifest(
            "document-splitter",
            "Tika Document Parser and LangChain4j Splitter",
            "1.0.0",
            "1.0",
            "com.core.docintel.plugins.TikaDocumentSplitterPlugin",
            "File",
            "SegmentCollection");

    private final DocumentParser parser;
    private final DocumentSplitter splitter;

    public TikaDocumentSplitterPlugin() {
        this(Integer.getInteger("plugin.document-splitter.segment-size", 1000),
                Integer.getInteger("plugin.document-splitter.segment-overlap", 100));
    }

    public TikaDocumentSplitterPlugin(int segmentSize, int segmentOverlap) {
        if (segmentSize <= 0) {
            throw new IllegalArgumentException("Segment size must be greater than zero.");
        }
        if (segmentOverlap < 0 || segmentOverlap >= segmentSize) {
            throw new IllegalArgumentException("Segment overlap must be at least zero and smaller than the segment size.");
        }
        this.parser = new ApacheTikaDocumentParser();
        this.splitter = new DocumentByCharacterSplitter(segmentSize, segmentOverlap);
    }

    @Override
    public PluginManifest manifest() {
        return MANIFEST;
    }

    @Override
    public List<String> split(File documentFile) {
        if (documentFile == null || !documentFile.isFile() || !documentFile.canRead()) {
            throw new IllegalArgumentException("Document must be an existing readable file: " + documentFile);
        }

        try (var input = Files.newInputStream(documentFile.toPath())) {
            Document document = parser.parse(input);
            List<TextSegment> segments = splitter.split(document);
            return segments.stream().map(TextSegment::text).toList();
        } catch (IOException e) {
            throw new IllegalStateException("Could not read document: " + documentFile, e);
        } catch (RuntimeException e) {
            throw new IllegalStateException("Could not parse or split document: " + documentFile, e);
        }
    }
}
