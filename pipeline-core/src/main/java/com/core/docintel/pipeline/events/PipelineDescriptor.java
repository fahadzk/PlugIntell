package com.core.docintel.pipeline.events;
import java.util.*;
public record PipelineDescriptor(String id,Integer version,String workflow,List<Node> nodes,List<Edge> edges){
 public record Node(String id,String pluginId,String label,List<String> capabilities){}
 public record Edge(String from,String to){}
 public PipelineDescriptor{nodes=List.copyOf(nodes);edges=List.copyOf(edges);}
 public Map<String,Object> toMap(){Map<String,Object> m=new LinkedHashMap<>();m.put("id",id);m.put("version",version);m.put("pipelineId",id);m.put("pipelineVersion",version);m.put("name",workflow);m.put("workflow",workflow);m.put("nodes",nodes.stream().map(n->Map.of("id",n.id(),"pluginId",n.pluginId(),"label",n.label(),"capabilities",n.capabilities())).toList());m.put("edges",edges.stream().map(e->Map.of("from",e.from(),"to",e.to())).toList());return m;}
}

