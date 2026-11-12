package com.plugintell.graph;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
final class Json {
    static final ObjectMapper MAPPER = new ObjectMapper();
    static Object parse(String raw) { try { return MAPPER.readValue(raw,Object.class); } catch(Exception e) { throw new IllegalArgumentException("Invalid JSON."); } }
    static Map<String,Object> obj(Object... pairs) { Map<String,Object> m=new LinkedHashMap<>();for(int i=0;i<pairs.length;i+=2)m.put((String)pairs[i],pairs[i+1]);return m; }
    static Map<String,Object> object(Object value) { if(!(value instanceof Map<?,?> map))throw new IllegalArgumentException("Expected JSON object.");Map<String,Object> m=new LinkedHashMap<>();map.forEach((k,v)->m.put((String)k,v));return m; }
    static String string(Object x){return x instanceof String s?s:null;}
    static String firstString(Map<String,Object> m,String... keys){for(String k:keys){String s=string(m.get(k));if(s!=null&&!s.isBlank())return s;}return null;}
    static Integer integer(Object x){return x instanceof Number n?n.intValue():null;}
    static Double decimal(Object x){return x instanceof Number n?n.doubleValue():null;}
    static Boolean booleanValue(Object x){return x instanceof Boolean b?b:null;}
}
