package com.core.docintel.response;
import java.time.*;
import java.util.*;
import static com.core.docintel.response.ResponseModel.*;

/** Responses and their artifacts are evicted together, bounded by count, age and total bytes. */
public final class ResponseStore {
    public record Entry(String id,String query,String createdAt,byte[] json,Map<String,ArtifactManager.File> files,long bytes,Instant storedAt){}
    private final LinkedHashMap<String,Entry> entries=new LinkedHashMap<>();private final ResponseConfig config;private final Clock clock;private long bytes;
    public ResponseStore(ResponseConfig config){this(config,Clock.systemUTC());}
    ResponseStore(ResponseConfig config,Clock clock){this.config=config;this.clock=clock;}
    public synchronized Response put(Response base,List<ArtifactManager.File> files){
        cleanup();var references=files.stream().map(ArtifactManager.File::descriptor).toList();var response=new Response(base.schemaVersion(),base.id(),base.createdAt(),base.query(),base.status(),base.blocks(),base.sources(),base.metadata(),base.warnings(),references);byte[] json=ResponseJson.bytes(response);
        if(json.length>config.maxResponseBytes())throw new IllegalArgumentException("Normalized response exceeds UR_MAX_RESPONSE_BYTES.");
        long size=json.length+files.stream().mapToLong(f->f.bytes().length).sum();if(size>config.maxStoreBytes())throw new IllegalArgumentException("Response and artifacts exceed the configured memory budget.");
        if(entries.containsKey(response.id()))remove(response.id());
        while(!entries.isEmpty()&&(entries.size()>=config.maxResponses()||bytes+size>config.maxStoreBytes()))remove(entries.keySet().iterator().next());
        Map<String,ArtifactManager.File> artifacts=new HashMap<>();files.forEach(f->artifacts.put(f.id(),f));entries.put(response.id(),new Entry(response.id(),response.query(),response.createdAt(),json,Map.copyOf(artifacts),size,clock.instant()));bytes+=size;return response;
    }
    public synchronized Entry get(String id){cleanup();return entries.get(id);}
    public synchronized Entry latest(){cleanup();return entries.isEmpty()?null:entries.values().stream().reduce((a,b)->b).orElse(null);}
    public synchronized ArtifactManager.File artifact(String id){cleanup();return entries.values().stream().map(e->e.files().get(id)).filter(Objects::nonNull).findFirst().orElse(null);}
    public synchronized List<Map<String,Object>> list(){cleanup();return entries.values().stream().map(e->Map.<String,Object>of("id",e.id(),"query",e.query(),"createdAt",e.createdAt())).toList();}
    public synchronized void cleanup(){var expired=entries.entrySet().stream().filter(e->e.getValue().storedAt().plusSeconds(config.ageMinutes()*60L).isBefore(clock.instant())).map(Map.Entry::getKey).toList();expired.forEach(this::remove);}
    private void remove(String id){var entry=entries.remove(id);if(entry!=null)bytes-=entry.bytes();}
    public synchronized void clear(){entries.clear();bytes=0;}
    public synchronized int size(){cleanup();return entries.size();}
    public synchronized long bytesUsed(){cleanup();return bytes;}
}
