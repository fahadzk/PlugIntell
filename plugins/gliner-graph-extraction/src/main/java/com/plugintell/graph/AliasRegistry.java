package com.plugintell.graph;
import java.text.Normalizer;
import java.util.*;
final class AliasRegistry {
    private final Map<String,String> names=new HashMap<>();
    AliasRegistry(String json,GraphSchema schema){
        Json.object(Json.parse(json)).forEach((type,raw)->{
            var declared=schema.entity(type);if(declared==null)throw new IllegalArgumentException("Alias type is not declared in schema.");
            Json.object(raw).forEach((canonical,aliases)->{
                if(!(aliases instanceof List<?> list))throw new IllegalArgumentException("Aliases must be arrays.");
                register(declared.name(),canonical,canonical);
                for(Object alias:list){if(!(alias instanceof String s))throw new IllegalArgumentException("Aliases must be strings.");register(declared.name(),s,canonical);}
            });
        });
    }
    private void register(String type,String alias,String canonical){String key=type+"\0"+identity(alias);String value=clean(canonical);if(value.isBlank()||identity(alias).isBlank())throw new IllegalArgumentException("Empty alias.");String prior=names.putIfAbsent(key,value);if(prior!=null&&!prior.equals(value))throw new IllegalArgumentException("Ambiguous alias.");}
    String canonical(String type,String name){return names.getOrDefault(type+"\0"+identity(name),clean(name));}
    static String clean(String s){return Normalizer.normalize(s,Normalizer.Form.NFKC).strip().replaceAll("\\s+"," ");}
    static String identity(String s){return clean(s).toLowerCase(Locale.ROOT);}
}
