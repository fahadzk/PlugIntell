package com.core.docintel.plugins;

import com.core.docintel.pipeline.api.*;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.splitter.DocumentByCharacterSplitter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Splits already extracted text while preserving document, source and page provenance. */
public final class LangChain4jSplitterPlugin extends AbstractPlugin implements ConfigurablePlugin {
    private int size=1000,overlap=100;
    private DocumentByCharacterSplitter splitter;
    public LangChain4jSplitterPlugin() { this(PluginMessages.noop()); }
    public LangChain4jSplitterPlugin(PluginMessages messages) {
        super(messages);
        configure(Map.of("PLUGIN_LANGCHAIN4J_CHUNK_SIZE",System.getenv().getOrDefault("PLUGIN_LANGCHAIN4J_CHUNK_SIZE","1000"),
            "PLUGIN_LANGCHAIN4J_CHUNK_OVERLAP",System.getenv().getOrDefault("PLUGIN_LANGCHAIN4J_CHUNK_OVERLAP","100")));
    }
    public PluginManifest manifest() {
        return new PluginManifest("langchain4j-splitter","LangChain4j Splitter","1.0.0","1.0",getClass().getName(),"TextDocument","ChunkCollection");
    }
    public void configure(Map<String,Object> config) {
        int nextSize=Integer.parseInt(config.getOrDefault("PLUGIN_LANGCHAIN4J_CHUNK_SIZE",size).toString());
        int nextOverlap=Integer.parseInt(config.getOrDefault("PLUGIN_LANGCHAIN4J_CHUNK_OVERLAP",overlap).toString());
        if(nextSize<=0||nextOverlap<0||nextOverlap>=nextSize)
            throw new IllegalArgumentException("Chunk overlap must be non-negative and smaller than a positive chunk size.");
        var next=new DocumentByCharacterSplitter(nextSize,nextOverlap);
        size=nextSize;overlap=nextOverlap;splitter=next;
    }
    public Object execute(Object input) {
        if(!(input instanceof TextDocument document))throw new IllegalArgumentException("langchain4j-splitter expects a TextDocument.");
        List<TextChunk> chunks=new ArrayList<>();
        emit("splitting.started",Map.of());
        for(var page:document.pages()) {
            if(page.text().isBlank())continue;
            for(var segment:splitter.split(Document.from(page.text()))) {
                int ordinal=chunks.size();String text=segment.text();
                chunks.add(new TextChunk(digest(document.documentId()+"\n"+page.pageNumber()+"\n"+ordinal+"\n"+text),document.documentId(),document.sourceName(),page.pageNumber(),ordinal,text));
            }
        }
        emit("splitting.completed",Map.of("chunkCount",chunks.size()));
        return new ChunkCollection(document.documentId(),chunks);
    }
    private static String digest(String value) {
        try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException("SHA-256 is unavailable.",e);}
    }
}
