package com.core.docintel.pipeline.builder;

import java.util.Comparator;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Per-execution redaction of resolved secret values before errors are persisted or emitted. */
final class SecretRedactor {
    private final Set<String> secrets=ConcurrentHashMap.newKeySet();
    void register(String secret){if(secret!=null&&!secret.isEmpty())secrets.add(secret);}
    String redact(String message){
        String result=message==null?"":message;
        for(String secret:secrets.stream().filter(value->value.length()>=4).sorted(Comparator.comparingInt(String::length).reversed()).toList())result=result.replace(secret,"[REDACTED]");
        return result;
    }
}
