package com.core.docintel.pipeline.core;

import com.core.docintel.pipeline.api.Plugin;
import com.core.docintel.pipeline.api.ManagedPlugin;
import java.util.List;
import java.util.*;
import com.core.docintel.pipeline.events.RunEvents;

public final class Pipeline implements AutoCloseable {
    private final List<Plugin> plugins;
    private final RunEvents events;
    private final List<String> nodeIds;
    private final boolean ownsRun;
    public Pipeline(List<Plugin> plugins) { this(plugins,new RunEvents(UUID.randomUUID().toString(),null,null,null,e->{}),plugins.stream().map(p->p.manifest().id()).toList(),true); }
    public Pipeline(List<Plugin> plugins,RunEvents events,List<String> nodeIds){this(plugins,events,nodeIds,false);}
    private Pipeline(List<Plugin> plugins,RunEvents events,List<String> nodeIds,boolean ownsRun){this.plugins=List.copyOf(plugins);this.events=events;this.nodeIds=List.copyOf(nodeIds);this.ownsRun=ownsRun;if(plugins.size()!=nodeIds.size())throw new IllegalArgumentException("Node identities must match plugins.");}
    public Object execute(Object initialInput) {
        RunEvents events=ownsRun?new RunEvents(UUID.randomUUID().toString(),null,null,null,e->{}):this.events;
        Object current = initialInput;
        if(ownsRun)events.event(null,null,"RUN_STARTED","Run started",Map.of(),null);
        try{
            for(int i=0;i<plugins.size();i++){
                Plugin plugin=plugins.get(i);String node=nodeIds.get(i),id=plugin.manifest().id();
                if(Thread.currentThread().isInterrupted()){events.event(null,null,"CANCELLATION_REQUESTED","Interruption requested",Map.of(),null);throw new java.util.concurrent.CancellationException("Cancellation requested.");}
                events.event(node,id,"NODE_STARTED","Node started",Map.of("inputSummary",com.core.docintel.pipeline.events.DataSummaries.of(current)),null);
                RunEvents.Attempt attempt=events.currentAttempt(node);if(attempt==null)attempt=events.prepareAttempt(node,id,1);attempt.start();long start=System.nanoTime();
                try{current=plugin.execute(current);attempt.finish("ATTEMPT_SUCCEEDED","Attempt completed",elapsed(start));events.event(node,id,"NODE_SUCCEEDED","Node completed",Map.of("outputSummary",com.core.docintel.pipeline.events.DataSummaries.of(current)),elapsed(start));}
                catch(RuntimeException failure){if(events.cancellationRequested()||failure instanceof java.util.concurrent.CancellationException||Thread.currentThread().isInterrupted()){attempt.close();events.event(node,id,"CANCELLATION_REQUESTED","Attempt interruption requested",Map.of(),null);events.event(node,id,"NODE_CANCELLED","Node stopped",Map.of(),elapsed(start));}else{attempt.finish("ATTEMPT_FAILED",failure.getClass().getSimpleName(),elapsed(start));events.event(node,id,"NODE_FAILED",failure.getMessage(),Map.of(),elapsed(start));}throw failure;}
                finally{attempt.lifecycle("ATTEMPT_TERMINATED","Attempt call terminated",elapsed(start));attempt.close();}
                if(i+1<plugins.size())events.event(node,id,"HANDOFF","Output passed to next node",Map.of("fromNodeId",node,"toNodeId",nodeIds.get(i+1)),null);
                System.out.printf("%-18s OK%n",id);
            }
            if(ownsRun)events.event(null,null,"RUN_SUCCEEDED","Run completed",Map.of(),null);return current;
        }catch(RuntimeException failure){if(ownsRun)events.event(null,null,events.cancellationRequested()||failure instanceof java.util.concurrent.CancellationException?"RUN_CANCELLED":"RUN_FAILED",failure.getClass().getSimpleName(),Map.of(),null);throw failure;}
    }
    private static long elapsed(long start){return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start);}

    @Override
    public void close() {
        events.closeReports();
        RuntimeException failure = null;
        for (int i = plugins.size() - 1; i >= 0; i--) {
            if (plugins.get(i) instanceof ManagedPlugin managed) {
                try {
                    managed.close();
                } catch (RuntimeException e) {
                    if (failure == null) failure = new IllegalStateException("Could not close pipeline plugin resources.", e);
                    else failure.addSuppressed(e);
                }
            }
        }
        if (failure != null) throw failure;
    }
}
