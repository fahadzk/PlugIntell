package com.core.docintel.text2cypher;
import com.core.docintel.neo4j.GraphPolicy;
import java.util.*;

/** Deliberate redaction before plugin events/logging, in addition to the host's redactor. */
final class RetrievalAudit {
    private final List<String> secrets=new ArrayList<>();
    RetrievalAudit(Map<String,Object> config){config.forEach((key,value)->{if(key.matches("(?i).*(PASSWORD|API_KEY|TOKEN|SECRET|CREDENTIAL).*")){String text=Objects.toString(value,"");if(!text.isBlank()&&!text.startsWith("secret://"))secrets.add(text);}});secrets.sort(Comparator.comparingInt(String::length).reversed());}
    String text(String value){String out=value;for(String secret:secrets)out=out.replace(secret,"[REDACTED]");return out.replaceAll("(?i)Bearer\\s+[A-Za-z0-9._~+/-]+=*","Bearer [REDACTED]");}
    Object sanitize(Object value,String key){
        if(key.matches("(?i).*(PASSWORD|API_KEY|TOKEN|SECRET|CREDENTIAL|AUTHORIZATION).*"))return "[REDACTED]";
        if(value instanceof Map<?,?> map){Map<String,Object> out=new LinkedHashMap<>();map.forEach((k,v)->out.put(k.toString(),sanitize(v,k.toString())));return out;}
        if(value instanceof Collection<?> items)return items.stream().map(v->sanitize(v,key)).toList();
        if(value instanceof String s){String safe=text(s);return safe.length()>512?safe.substring(0,512)+"…":safe;}
        return value;
    }
    String json(Object value){return GraphPolicy.encode(sanitize(GraphPolicy.JSON.convertValue(value,Object.class),""));}
}
