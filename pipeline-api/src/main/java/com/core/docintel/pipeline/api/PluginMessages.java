package com.core.docintel.pipeline.api;
import java.util.Map;
@FunctionalInterface
public interface PluginMessages {
    PluginMessages NONE=message->{};
    void report(PluginMessage message);
    static PluginMessages noop(){return NONE;}
    default void emit(String code,Map<String,Object> payload){report(new PluginMessage(code,"INFO",code,payload));}
    default void progress(long completed,long total,String unit){progress(completed,Long.valueOf(total),unit);}
    default void progress(long completed,Long total,String unit){
        if(completed<0||total!=null&&(total<0||completed>total))throw new IllegalArgumentException("Invalid progress counts.");
        var metrics=new java.util.LinkedHashMap<String,Object>();metrics.put("completed",completed);metrics.put("total",total);metrics.put("unit",unit);
        report(new PluginMessage("progress","INFO","Progress",metrics));
    }
    default void info(String message){report(new PluginMessage("info","INFO",message,Map.of()));}
    default void warning(String message){report(new PluginMessage("warning","WARN",message,Map.of()));}
}

