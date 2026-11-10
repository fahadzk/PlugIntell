package com.core.docintel.pipeline.events;

import java.io.PrintStream;
import java.util.*;

/** CLI delivery diagnostics use fixed fields; adapter health may contain arbitrary private details. */
public final class EventDiagnostics {
    private EventDiagnostics(){}
    public static void report(List<Map<String,Object>> destinations,boolean verbose,PrintStream output){
        for(Map<String,Object> destination:destinations){
            Map<?,?> adapter=destination.get("adapter") instanceof Map<?,?> value?value:Map.of();
            long failed=count(destination,"failed"),dropped=count(destination,"dropped"),brokerFailed=count(adapter,"failed");
            if(!verbose&&failed==0&&dropped==0&&brokerFailed==0)continue;
            String id=Objects.toString(destination.get("id"),"adapter").replaceAll("[^A-Za-z0-9_.-]","_");
            output.println("Event adapter "+id+": delivered="+count(destination,"delivered")+", queued="+count(destination,"queued")+", dropped="+dropped+", coalesced="+count(destination,"coalesced")+", failed="+failed+(adapter.containsKey("delivered")?", destinationDelivered="+count(adapter,"delivered"):"")+(adapter.containsKey("failed")?", destinationFailed="+brokerFailed:""));
        }
    }
    private static long count(Map<?,?> value,String key){return value.get(key) instanceof Number number?number.longValue():0;}
}
