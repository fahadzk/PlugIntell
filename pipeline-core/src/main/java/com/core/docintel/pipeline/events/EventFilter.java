package com.core.docintel.pipeline.events;
import java.util.Set;
public record EventFilter(Set<String> nodeIds,Set<String> eventTypes,Set<String> severities){
 public EventFilter{nodeIds=nodeIds==null?Set.of():Set.copyOf(nodeIds);eventTypes=eventTypes==null?Set.of():Set.copyOf(eventTypes);severities=severities==null?Set.of():Set.copyOf(severities);}
 public static EventFilter all(){return new EventFilter(null,null,null);}
 public boolean matches(ExecutionEvent e){return (nodeIds.isEmpty()||e.nodeId()!=null&&nodeIds.contains(e.nodeId()))&&(eventTypes.isEmpty()||eventTypes.contains(e.type()))&&(severities.isEmpty()||severities.contains(e.severity()));}
}

