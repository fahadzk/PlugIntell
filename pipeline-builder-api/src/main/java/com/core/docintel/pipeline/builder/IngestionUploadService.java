package com.core.docintel.pipeline.builder;

import io.micronaut.context.annotation.Value;
import io.micronaut.http.multipart.CompletedFileUpload;
import jakarta.inject.Singleton;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.util.UUID;

/** Stores uploads only under the same root accepted by ingestion execution. */
@Singleton
public final class IngestionUploadService {
    private final Path root;
    private final long maximumBytes;

    public IngestionUploadService(@Value("${pipeline.execution.input-root:./data/ingestion}") String inputRoot,
                                  @Value("${pipeline.execution.maximum-upload-bytes:52428800}") long maximumBytes) {
        this.root=Path.of(inputRoot).toAbsolutePath().normalize();
        if(maximumBytes<1)throw new IllegalArgumentException("Maximum upload size must be positive.");
        this.maximumBytes=maximumBytes;
    }

    public UploadedFile save(CompletedFileUpload upload){
        if(upload==null)throw new IllegalArgumentException("An upload file is required.");
        String original=upload.getFilename()==null?"document":Path.of(upload.getFilename().replace('\\','/')).getFileName().toString();
        if(original.isBlank()||original.equals(".")||original.equals(".."))original="document";
        if(upload.getSize()<1)throw new IllegalArgumentException("The uploaded file is empty.");
        if(upload.getSize()>maximumBytes)throw new IllegalArgumentException("Upload exceeds the maximum size of "+maximumBytes+" bytes.");
        try{
            Files.createDirectories(root);Path realRoot=root.toRealPath();
            String safeName=original.replaceAll("[^A-Za-z0-9_.-]","_");
            if(safeName.length()>120)safeName=safeName.substring(safeName.length()-120);
            Path target=realRoot.resolve(UUID.randomUUID()+"-"+safeName).normalize();
            if(!target.startsWith(realRoot))throw new IllegalArgumentException("Invalid upload filename.");
            Path temporary=Files.createTempFile(realRoot,".upload-",".tmp");long copied=0;
            try(InputStream in=upload.getInputStream();var out=Files.newOutputStream(temporary,StandardOpenOption.WRITE,StandardOpenOption.TRUNCATE_EXISTING)){
                byte[] buffer=new byte[8192];int count;
                while((count=in.read(buffer))!=-1){copied+=count;if(copied>maximumBytes)throw new IllegalArgumentException("Upload exceeds the maximum size of "+maximumBytes+" bytes.");out.write(buffer,0,count);}
            }catch(IOException|RuntimeException error){Files.deleteIfExists(temporary);throw error;}
            try{Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE);}catch(AtomicMoveNotSupportedException ignored){Files.move(temporary,target);}
            return new UploadedFile(target.getFileName().toString(),original,copied);
        }catch(IOException e){throw new IllegalStateException("Could not store uploaded file: "+e.getMessage(),e);}
    }
}
