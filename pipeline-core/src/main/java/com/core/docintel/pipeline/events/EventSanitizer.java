package com.core.docintel.pipeline.events;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
public final class EventSanitizer {
 private final Set<String> secrets=ConcurrentHashMap.newKeySet();
 public EventSanitizer(){System.getenv().forEach((k,v)->{if(k.matches("(?i).*(PASS|PWD|TOKEN|SECRET|KEY|CREDENTIAL|AUTH).*"))register(v);});}
 public void register(String value){if(value!=null&&!value.isEmpty())secrets.add(value);}
 public String text(String text){String result=Objects.toString(text,"");for(String secret:secrets.stream().sorted(Comparator.comparingInt(String::length).reversed()).toList())result=result.replace(secret,"[REDACTED]");return result.substring(0,Math.min(1000,result.length()));}
 public Map<String,Object> payload(Map<String,?> values){return cleanMap(values,0);}
 private Map<String,Object> cleanMap(Map<?,?> values,int depth){Map<String,Object> out=new LinkedHashMap<>();if(values==null||depth>4)return out;values.entrySet().stream().limit(64).forEach(e->{String key=text(String.valueOf(e.getKey()));out.put(key,key.matches("(?i).*(password|secret|token|credential|prompt|content|embedding|rawResponse|documentText).*")?"[REDACTED]":clean(e.getValue(),depth+1));});return Collections.unmodifiableMap(out);}
 private Object clean(Object v,int depth){if(v==null)return null;if(v instanceof Map<?,?> m)return cleanMap(m,depth);if(v instanceof Collection<?> c)return depth>4?List.of():c.stream().limit(64).map(x->clean(x,depth+1)).toList();if(v instanceof Number n)return Double.isFinite(n.doubleValue())?n:null;if(v instanceof Boolean)return v;return text(String.valueOf(v));}
}

