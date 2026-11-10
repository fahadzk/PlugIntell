package com.core.docintel.pipeline.events;
import java.util.*;
/** UI projection keeps canonical sequence cursors, including hidden events, without inventing state. */
public final class EventView{
 private final List<EventFilter> filters;private final boolean implicit;
 public EventView(List<EventFilter> filters,boolean implicit){this.filters=List.copyOf(filters);this.implicit=implicit;}
 private boolean selected(Map<String,Object> e){return implicit||filters.stream().anyMatch(f->(f.nodeIds().isEmpty()||e.get("nodeId")!=null&&f.nodeIds().contains(e.get("nodeId")))&&(f.eventTypes().isEmpty()||f.eventTypes().contains(e.get("type")))&&(f.severities().isEmpty()||f.severities().contains(e.get("severity"))));}
 public Map<String,Object> event(Map<String,Object> event){Map<String,Object> result=new LinkedHashMap<>(event);boolean visible=selected(event);result.put("visible",visible);if(!visible){result.put("message","");result.put("code","");if(Set.of("PLUGIN_MESSAGE","PROGRESS").contains(event.get("type")))result.put("payload",Map.of());}return result;}
 @SuppressWarnings("unchecked")
 public Map<String,Object> snapshot(Map<String,Object> raw,List<Map<String,Object>> retained){
  Map<String,Object> result=new LinkedHashMap<>(raw),nodes=new LinkedHashMap<>();
  Map<String,Map<String,Object>> progress=new HashMap<>();
  Set<Object> closed=new HashSet<>();for(Map<String,Object> e:retained){if(Set.of("ATTEMPT_SUCCEEDED","ATTEMPT_FAILED","ATTEMPT_TIMEOUT","ATTEMPT_TERMINATED").contains(e.get("type")))closed.add(e.get("attemptId"));if("PROGRESS".equals(e.get("type"))&&!closed.contains(e.get("attemptId"))&&selected(e))progress.put((String)e.get("nodeId"),e);}
  ((Map<String,Object>)raw.get("nodes")).forEach((id,value)->{
   Map<String,Object> node=new LinkedHashMap<>((Map<String,Object>)value);Map<String,Object> p=progress.get(id);
   for(Object record:((Map<String,Object>)node.getOrDefault("progressBySeverity",Map.of())).values()){
    Map<String,Object> candidate=(Map<String,Object>)record;
    if(selected(candidate)&&Objects.equals(node.get("attemptId"),candidate.get("attemptId"))&&(p==null||((Number)candidate.get("sequence")).longValue()>((Number)p.get("sequence")).longValue()))p=candidate;
   }
   if(p!=null&&Objects.equals(node.get("attemptId"),p.get("attemptId")))node.put("progress",p.get("payload"));else if(!implicit)node.remove("progress");
   node.remove("progressBySeverity");nodes.put(id,node);
  });
  result.put("nodes",nodes);List<Map<String,Object>> tail=(List<Map<String,Object>>)raw.getOrDefault("events",List.of());result.put("events",tail.stream().map(this::event).toList());result.remove("capabilities");return result;
 }
}

