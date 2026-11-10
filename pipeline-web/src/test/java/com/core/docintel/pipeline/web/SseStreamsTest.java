package com.core.docintel.pipeline.web;
import com.core.docintel.pipeline.events.*;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class SseStreamsTest{
 static final PipelineDescriptor DESCRIPTOR=new PipelineDescriptor("p",1,"QUERY_PROCESSING",List.of(new PipelineDescriptor.Node("a","fixture","A",List.of())),List.of());
 @Test void replayCursorGapAndSubscriptionCleanup(){
  var store=new WorkflowStateStore("r",DESCRIPTOR,2);var events=new RunEvents("r","p",1,null,store::accept);events.event(null,null,"RUN_STARTED","run",Map.of(),null);var scope=events.attempt("a","fixture",1);scope.progress(1,2L,"items");
  var replay=SseStreams.stream(2,store::snapshot,n->store.after(n).stream().map(ExecutionEvent::toMap).toList(),store::gap).blockFirst(Duration.ofSeconds(2));assertNotNull(replay);assertEquals("execution",replay.getName());assertEquals("3",replay.getId());
  var gap=SseStreams.stream(0,store::snapshot,n->store.after(n).stream().map(ExecutionEvent::toMap).toList(),store::gap).blockFirst(Duration.ofSeconds(2));assertEquals("gap",gap.getName());assertEquals(3L,gap.getData().get("sequence"));
  assertThrows(IllegalArgumentException.class,()->SseStreams.stream(-1,store::snapshot,n->List.of(),store::gap));
  for(int i=0;i<100&&SseStreams.activeSubscriptions()!=0;i++)try{Thread.sleep(5);}catch(InterruptedException e){Thread.currentThread().interrupt();}
  assertEquals(0,SseStreams.activeSubscriptions());
 }
 @Test void eventsCreatedAfterSnapshotReplayWithoutLostSubscribeWindow(){
  var store=new WorkflowStateStore("r",DESCRIPTOR,10);var events=new RunEvents("r","p",1,null,store::accept);events.event(null,null,"RUN_STARTED","run",Map.of(),null);
  long cursor=((Number)store.snapshot().get("sequence")).longValue();events.attempt("a","fixture",1);
  var message=SseStreams.stream(cursor,store::snapshot,n->store.after(n).stream().map(ExecutionEvent::toMap).toList(),store::gap).blockFirst(Duration.ofSeconds(2));assertEquals("2",message.getId());
 }
 @Test void missingEventInsideReplayPageResetsToSnapshot(){
  var messages=SseStreams.stream(0,()->Map.of("sequence",4L),n->List.of(Map.of("sequence",1L),Map.of("sequence",3L),Map.of("sequence",4L)),n->false).take(2).collectList().block(Duration.ofSeconds(2));
  assertEquals(List.of("execution","gap"),messages.stream().map(e->e.getName()).toList());
  assertEquals("4",messages.getLast().getId());
 }
}

