package com.core.docintel.pipeline.events;
import java.util.*;
/** Explicit JSON serialization of the protocol's primitive/map/list values, without reflection. */
public final class EventJson {
 private EventJson(){}
 public static String write(Object value){
  if(value==null)return "null";
  if(value instanceof Boolean||value instanceof Number)return value.toString();
  if(value instanceof Map<?,?> map){var fields=new ArrayList<String>();map.forEach((k,v)->fields.add(write(String.valueOf(k))+":"+write(v)));return "{"+String.join(",",fields)+"}";}
  if(value instanceof Collection<?> list)return "["+String.join(",",list.stream().map(EventJson::write).toList())+"]";
  String s=String.valueOf(value);StringBuilder b=new StringBuilder("\"");
  for(char c:s.toCharArray())switch(c){case '"'->b.append("\\\"");case '\\'->b.append("\\\\");case '\n'->b.append("\\n");case '\r'->b.append("\\r");case '\t'->b.append("\\t");default->{if(c<32)b.append(String.format("\\u%04x",(int)c));else b.append(c);}}
  return b.append('"').toString();
 }
}

