package com.core.docintel.pipeline.web;
import io.micronaut.http.sse.Event;
import reactor.core.publisher.Flux;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
/** Cursor polling avoids snapshot/subscribe races and unbounded subscriber queues. */
public final class SseStreams{
 private static final AtomicInteger active=new AtomicInteger();
 private SseStreams(){}
 public static Flux<Event<Map<String,Object>>> stream(long after,Supplier<Map<String,Object>> snapshot,LongFunction<List<Map<String,Object>>> replay,LongPredicate gap){
  if(after<0)throw new IllegalArgumentException("afterSequence must be nonnegative");
  return Flux.defer(()->{if(active.incrementAndGet()>128){active.decrementAndGet();return Flux.error(new IllegalStateException("Monitor subscription limit reached."));}
   AtomicLong cursor=new AtomicLong(after),ticks=new AtomicLong();
   return Flux.interval(Duration.ZERO,Duration.ofMillis(300)).onBackpressureLatest().publishOn(reactor.core.scheduler.Schedulers.boundedElastic()).concatMapIterable(tick->{List<Event<Map<String,Object>>> out=new ArrayList<>();
    if(gap.test(cursor.get())){Map<String,Object> state=snapshot.get();cursor.set(((Number)state.get("sequence")).longValue());out.add(Event.of(state).name("gap").id(Long.toString(cursor.get())));}
    else {List<Map<String,Object>> page=replay.apply(cursor.get());for(Map<String,Object> e:page){long sequence=((Number)e.get("sequence")).longValue();if(sequence>cursor.get()+1){Map<String,Object> state=snapshot.get();cursor.set(((Number)state.get("sequence")).longValue());out.add(Event.of(state).name("gap").id(Long.toString(cursor.get())));break;}if(sequence>cursor.get()){cursor.set(sequence);out.add(Event.of(e).name("execution").id(Long.toString(sequence)));}}}
    if(out.isEmpty()&&ticks.incrementAndGet()%50==0)out.add(Event.of(Map.<String,Object>of("sequence",cursor.get())).name("heartbeat"));
    return out;
   }).doFinally(signal->active.decrementAndGet());
  });
 }
 public static int activeSubscriptions(){return active.get();}
}

