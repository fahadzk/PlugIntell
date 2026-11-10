package com.core.docintel.plugins;
import com.core.docintel.pipeline.api.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class LangChain4jSplitterTest {
    @TempDir Path dir;
    @Test void parserAndSplitterComposeAndPreserveProvenance() throws Exception {
        Path file=dir.resolve("history.html");Files.writeString(file,"<html><body><p>"+"The Middle East has a long history. ".repeat(8)+"</p></body></html>");
        var parser=new TikaDocumentParserPlugin();var text=(TextDocument)parser.execute(file.toFile());
        var splitter=new LangChain4jSplitterPlugin();splitter.configure(Map.of("PLUGIN_LANGCHAIN4J_CHUNK_SIZE",64,"PLUGIN_LANGCHAIN4J_CHUNK_OVERLAP",8));
        var chunks=(ChunkCollection)splitter.execute(text);
        assertEquals(parser.manifest().outputType(),splitter.manifest().inputType());
        assertTrue(chunks.chunks().size()>1);
        for(int i=0;i<chunks.chunks().size();i++) {var c=chunks.chunks().get(i);assertEquals(text.documentId(),c.documentId());assertEquals("history.html",c.sourceName());assertEquals(1,c.pageNumber());assertEquals(i,c.ordinal());assertTrue(c.text().length()<=64);}
        assertEquals(chunks,splitter.execute(text));
    }
    @Test void preservesSuppliedPagesAndRejectsInvalidConfiguration() {
        var splitter=new LangChain4jSplitterPlugin();
        var result=(ChunkCollection)splitter.execute(new TextDocument("doc","source.pdf",List.of(new TextPage(1,""),new TextPage(4,"Useful text"))));
        assertEquals(4,result.chunks().getFirst().pageNumber());
        assertThrows(IllegalArgumentException.class,()->splitter.configure(Map.of("PLUGIN_LANGCHAIN4J_CHUNK_SIZE",10,"PLUGIN_LANGCHAIN4J_CHUNK_OVERLAP",10)));
        assertThrows(IllegalArgumentException.class,()->splitter.execute("text"));
        assertTrue(((ChunkCollection)splitter.execute(new TextDocument("empty","empty.txt",List.of()))).chunks().isEmpty());
    }
}
