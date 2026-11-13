package com.core.docintel.response;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ResponseStoreTest {
    static ResponseModel.Response response(String content){return new ResponseModel.Response("1.0",ResponseModel.id(),Instant.now().toString(),"q","complete",List.of(ResponseModel.block("markdown",content)),List.of(),Map.of(),List.of(),List.of());}
    @Test void countAndMemoryEvictionRemoveAssociatedArtifacts(){
        var store=new ResponseStore(ResponseConfig.from(Map.of("UR_MAX_RESPONSES",1)));var file=new ArtifactManager.File(ResponseModel.id(),"data.csv","text/csv",new byte[16]);var first=store.put(response("first"),List.of(file));store.put(response("second"),List.of());assertNull(store.get(first.id()));assertNull(store.artifact(file.id()));assertEquals(1,store.size());
        var bounded=new ResponseStore(ResponseConfig.from(Map.of("UR_MAX_STORE_BYTES",1048576)));bounded.put(response("one"),List.of(new ArtifactManager.File(ResponseModel.id(),"large.json","application/json",new byte[700000])));bounded.put(response("two"),List.of(new ArtifactManager.File(ResponseModel.id(),"large.json","application/json",new byte[700000])));assertEquals(1,bounded.size());assertTrue(bounded.bytesUsed()<1048576);
    }
    @Test void retentionUsesClockAndExpiredResponsesCannotBeDownloaded(){
        class MutableClock extends Clock {Instant now=Instant.now();public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){return now;}}
        var clock=new MutableClock();var store=new ResponseStore(ResponseConfig.from(Map.of("UR_MAX_AGE_MINUTES",1)),clock);store.put(response("hello"),List.of());clock.now=clock.now.plusSeconds(61);assertNull(store.latest());assertEquals(0,store.bytesUsed());
    }
    @Test void actualArtifactsHaveSafeCsvAndNonExecutableHtml(){
        var block=ResponseModel.block("table",RendererRegistry.table(List.of(Map.of("name","=HYPERLINK(\"evil\")","value",12))));var base=response("ignored");var response=new ResponseModel.Response(base.schemaVersion(),base.id(),base.createdAt(),"<script>evil</script>","complete",List.of(block),List.of(),Map.of(),List.of(),List.of());
        var files=ArtifactManager.standard().create(response);var csv=files.stream().filter(f->f.mime().equals("text/csv")).findFirst().orElseThrow();String data=new String(csv.bytes(),java.nio.charset.StandardCharsets.UTF_8);assertTrue(data.contains("'=HYPERLINK"));assertTrue(files.stream().anyMatch(f->f.name().equals("response.json")));String html=new String(files.stream().filter(f->f.mime().equals("text/html")).findFirst().orElseThrow().bytes(),java.nio.charset.StandardCharsets.UTF_8);assertFalse(html.contains("<script>"));assertTrue(html.contains("&lt;script&gt;"));
    }
}
