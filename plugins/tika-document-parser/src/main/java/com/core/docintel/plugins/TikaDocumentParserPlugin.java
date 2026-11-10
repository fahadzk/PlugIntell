package com.core.docintel.plugins;

import com.core.docintel.pipeline.api.*;
import dev.langchain4j.data.document.parser.apache.tika.ApacheTikaDocumentParser;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;

/** Parses a document into logical text; physical page numbers are not provided by this parser. */
public final class TikaDocumentParserPlugin extends AbstractPlugin {
    public TikaDocumentParserPlugin() {}
    public TikaDocumentParserPlugin(PluginMessages messages) { super(messages); }
    public PluginManifest manifest() {
        return new PluginManifest("tika-document-parser","Tika Document Parser","1.0.0","1.0",getClass().getName(),"File","TextDocument");
    }
    public Object execute(Object input) {
        if(!(input instanceof File file)||!file.isFile()||!file.canRead())
            throw new IllegalArgumentException("tika-document-parser expects an existing readable file.");
        try(var stream=Files.newInputStream(file.toPath())) {
            emit("parsing.started",Map.of());
            String text=new ApacheTikaDocumentParser().parse(stream).text();
            String id=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(file.toPath().toAbsolutePath().normalize().toString().getBytes(StandardCharsets.UTF_8)));
            emit("parsing.completed",Map.of("pageCount",1));
            return new TextDocument(id,file.getName(),List.of(new TextPage(1,text)));
        } catch(java.io.IOException e) {throw new IllegalStateException("Could not read document: "+file,e);}
        catch(java.security.NoSuchAlgorithmException e) {throw new IllegalStateException("SHA-256 is unavailable.",e);}
    }
}
