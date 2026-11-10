package com.core.docintel.plugins;
import com.core.docintel.pipeline.api.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;
class TikaDocumentParserTest {
    @TempDir Path dir;
    @Test void parsesHtmlAndPreservesSourceWithoutSplitting() throws Exception {
        Path file=dir.resolve("history.html");Files.writeString(file,"<html><body><h1>Middle East</h1><p>Historical documents and sources.</p></body></html>");
        var parser=new TikaDocumentParserPlugin();var result=(TextDocument)parser.execute(file.toFile());
        assertTrue(result.text().contains("Middle East"));assertFalse(result.text().contains("<h1>"));
        assertEquals("history.html",result.sourceName());assertEquals(1,result.pages().size());
        assertEquals(result.documentId(),((TextDocument)parser.execute(file.toFile())).documentId());
        assertEquals("File",parser.manifest().inputType());assertEquals("TextDocument",parser.manifest().outputType());
        assertThrows(IllegalArgumentException.class,()->parser.execute("text"));
        assertThrows(IllegalArgumentException.class,()->parser.execute(dir.resolve("absent.pdf").toFile()));
    }
}
