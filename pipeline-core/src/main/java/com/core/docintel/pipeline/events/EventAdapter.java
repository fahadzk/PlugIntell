package com.core.docintel.pipeline.events;
import java.time.Duration;
import java.util.Map;
/** Infrastructure destination. Never part of processing edges. */
public interface EventAdapter extends AutoCloseable {
 void accept(ExecutionEvent event) throws Exception;
 default Map<String,Object> health(){return Map.of("status","UP");}
 default void close(Duration timeout) throws Exception{close();}
 @Override default void close() throws Exception{}
}

