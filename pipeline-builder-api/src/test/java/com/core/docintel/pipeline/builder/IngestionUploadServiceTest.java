package com.core.docintel.pipeline.builder;

import io.micronaut.http.multipart.CompletedFileUpload;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.ByteArrayInputStream;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class IngestionUploadServiceTest {
    @TempDir Path temp;

    @Test void stripsClientPathAndStoresOnlyInsideConfiguredRoot() throws Exception {
        var service=new IngestionUploadService(temp.resolve("incoming").toString(),64);
        UploadedFile uploaded=service.save(file("..\\outside\\document.txt","safe contents"));
        assertFalse(uploaded.filePath().contains(".."));
        Path stored=temp.resolve("incoming").resolve(uploaded.filePath()).normalize();
        assertTrue(stored.startsWith(temp.resolve("incoming").normalize()));
        assertEquals("safe contents",Files.readString(stored));
        assertEquals("document.txt",uploaded.originalName());
    }

    @Test void rejectsOversizedUploadBeforeWriting() {
        var service=new IngestionUploadService(temp.resolve("incoming").toString(),4);
        assertThrows(IllegalArgumentException.class,()->service.save(file("large.txt","12345")));
        assertFalse(Files.exists(temp.resolve("incoming")));
    }

    private static CompletedFileUpload file(String filename,String text){
        byte[] bytes=text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return (CompletedFileUpload)Proxy.newProxyInstance(CompletedFileUpload.class.getClassLoader(),new Class<?>[]{CompletedFileUpload.class},(proxy,method,args)->switch(method.getName()){
            case "getFilename"->filename;
            case "getSize"->(long)bytes.length;
            case "getInputStream"->new ByteArrayInputStream(bytes);
            default->null;
        });
    }
}
