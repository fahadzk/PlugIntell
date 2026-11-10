package com.core.docintel.pipeline.web;

import com.core.docintel.pipeline.events.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.*;

/** Per-host console and bounded rolling files. No stdout capture or processing-plugin dependency. */
final class HostExecutionLogs implements AutoCloseable {
    private final Logger logger=Logger.getLogger("com.core.docintel.pipeline.host."+UUID.randomUUID());
    private final AtomicBoolean closed=new AtomicBoolean();
    private final EventSanitizer sanitizer=new EventSanitizer();
    private static final Set<String> COUNTS=Set.of("completed","total","chunkCount","pageCount","storedCount","retrievedCount","contextChunkCount");
    HostExecutionLogs(Path directory){
        logger.setUseParentHandlers(false);logger.setLevel(Level.INFO);
        java.util.logging.Formatter format=new java.util.logging.Formatter(){public String format(LogRecord record){return record.getInstant()+" "+record.getLevel()+" "+record.getMessage()+System.lineSeparator();}};
        ConsoleHandler console=new ConsoleHandler();console.setFormatter(format);logger.addHandler(console);
        try{Files.createDirectories(directory);FileHandler file=new FileHandler(directory.resolve("server-%g.log").toString(),5*1024*1024,3,true);file.setEncoding(StandardCharsets.UTF_8.name());file.setFormatter(format);logger.addHandler(file);info("Server execution logs: "+directory.toAbsolutePath().normalize());}
        catch(IOException|RuntimeException failure){logger.warning("File logging unavailable ("+failure.getClass().getSimpleName()+"); server console logging remains enabled.");}
    }
    EventAdapter adapter(){return event->{
        StringBuilder line=new StringBuilder("run=").append(safe(event.runId())).append(" seq=").append(event.sequence());
        if(event.nodeId()!=null)line.append(" node=").append(safe(event.nodeId()));
        if(event.pluginId()!=null)line.append(" plugin=").append(safe(event.pluginId()));
        if(event.attemptId()!=null)line.append(" attempt=").append(event.attempt()).append('/').append(safe(event.attemptId()));
        line.append(' ').append(safe(event.type())).append(" code=").append(safe(event.code()));
        if(event.message()!=null&&!event.message().equals(event.code()))line.append(" message=").append(safe(event.message()));
        for(String key:new TreeSet<>(COUNTS))if(event.payload().get(key) instanceof Number value)line.append(' ').append(key).append('=').append(value);
        if(event.durationMillis()!=null)line.append(" durationMs=").append(event.durationMillis());
        logger.log(switch(event.severity()){case "ERROR"->Level.SEVERE;case "WARN"->Level.WARNING;case "DEBUG"->Level.FINE;case "TRACE"->Level.FINEST;default->Level.INFO;},line.toString());
    };}
    void info(String message){logger.info(safe(message));}
    private String safe(String value){return sanitizer.text(value).replaceAll("\\p{Cntrl}"," ");}
    @Override public void close(){if(closed.compareAndSet(false,true)){info("Pipeline web server stopped.");for(Handler handler:logger.getHandlers()){handler.flush();handler.close();logger.removeHandler(handler);}}}
}
