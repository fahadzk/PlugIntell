package com.core.docintel.pipeline.builder;

import com.core.docintel.pipeline.events.EventAdapter;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/** One bounded SQLite telemetry destination. Processing only updates memory and offers records. */
final class StudioHistoryRecorder implements AutoCloseable {
    private static final int CAPACITY=4096, BATCH=128;
    private final ExecutionStore store;
    private final Deque<Entry> queue=new ArrayDeque<>();
    private final AtomicLong delivered=new AtomicLong(),dropped=new AtomicLong(),coalesced=new AtomicLong(),failed=new AtomicLong();
    private final Thread worker;
    private volatile boolean stopping;
    private volatile String lastFailure="";
    // SQLite uses native blocking calls; keep this single destination off Java 21 execution carriers.
    StudioHistoryRecorder(ExecutionStore store){this.store=store;worker=Thread.ofPlatform().daemon().name("studio-history").start(this::work);}
    RunDestination forRun(String runId,Supplier<Map<String,Object>> snapshot){return new RunDestination(runId,snapshot);}
    final class RunDestination implements EventAdapter {
        private final String runId;private final Supplier<Map<String,Object>> snapshot;
        RunDestination(String runId,Supplier<Map<String,Object>> snapshot){this.runId=runId;this.snapshot=snapshot;}
        @Override public void accept(com.core.docintel.pipeline.events.ExecutionEvent event){offer(new Entry(runId,snapshot,event,null));}
        @Override public Map<String,Object> health(){return StudioHistoryRecorder.this.health();}
    }
    void legacy(String runId,Supplier<Map<String,Object>> snapshot,ExecutionEvent event){offer(new Entry(runId,snapshot,null,event));}
    private synchronized void offer(Entry entry){
        if(stopping){dropped.incrementAndGet();return;}
        if(queue.size()>=CAPACITY){
            boolean removed=false;
            if(entry.event()!=null&&entry.event().type().equals("PROGRESS")){
                var it=queue.descendingIterator();
                while(it.hasNext()){Entry old=it.next();if(old.event()!=null&&old.runId().equals(entry.runId())&&old.event().type().equals("PROGRESS")&&Objects.equals(old.event().attemptId(),entry.event().attemptId())){it.remove();coalesced.incrementAndGet();removed=true;break;}}
            }
            if(!removed&&entry.terminal()){
                var it=queue.iterator();while(it.hasNext()){if(!it.next().terminal()){it.remove();dropped.incrementAndGet();removed=true;break;}}
            }
            if(!removed){dropped.incrementAndGet();return;}
        }
        queue.addLast(entry);notifyAll();
    }
    private void work(){
        while(true){
            List<Entry> batch=new ArrayList<>();
            synchronized(this){
                while(queue.isEmpty()&&!stopping)try{wait();}catch(InterruptedException e){Thread.currentThread().interrupt();return;}
                if(queue.isEmpty())return;
                while(!queue.isEmpty()&&batch.size()<BATCH)batch.add(queue.removeFirst());
            }
            Map<String,List<Entry>> groups=new LinkedHashMap<>();
            batch.forEach(e->groups.computeIfAbsent(e.runId(),key->new ArrayList<>()).add(e));
            for(var group:groups.entrySet()){
                List<Entry> entries=group.getValue();
                try{
                    Map<String,Object> snapshot=entries.getLast().snapshot().get();
                    store.persistTelemetry(group.getKey(),snapshot,entries.stream().map(Entry::event).filter(Objects::nonNull).toList(),entries.stream().map(Entry::legacy).filter(Objects::nonNull).toList());
                    delivered.addAndGet(entries.size());
                }catch(RuntimeException failure){failed.addAndGet(entries.size());lastFailure=failure.getClass().getSimpleName();}
            }
        }
    }
    synchronized Map<String,Object> health(){return Map.of("id","_studio-history","status",failed.get()>0?"DEGRADED":stopping?"STOPPED":"UP","queued",queue.size(),"delivered",delivered.get(),"dropped",dropped.get(),"coalesced",coalesced.get(),"failed",failed.get(),"lastFailure",lastFailure);}
    @Override public void close(){
        synchronized(this){stopping=true;notifyAll();}
        try{worker.join(Duration.ofSeconds(2));}catch(InterruptedException e){Thread.currentThread().interrupt();}
        if(worker.isAlive()){worker.interrupt();synchronized(this){dropped.addAndGet(queue.size());queue.clear();}}
    }
    private record Entry(String runId,Supplier<Map<String,Object>> snapshot,com.core.docintel.pipeline.events.ExecutionEvent event,ExecutionEvent legacy){
        boolean terminal(){return event!=null?event.terminal():Set.of("SUCCEEDED","FAILED","CANCELLED").contains(legacy.status());}
    }
}

