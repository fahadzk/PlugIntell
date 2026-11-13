package com.core.docintel.response;
import java.time.Instant;
import java.util.*;
public final class ResponseModel {
    private ResponseModel(){}
    public record Block(String id,String type,String renderer,Object data,Map<String,Object> options){}
    public record Source(String id,String name,int page,double score){}
    public record Artifact(String id,String name,String mime,long bytes){}
    public record Response(String schemaVersion,String id,String createdAt,String query,String status,
        List<Block> blocks,List<Source> sources,Map<String,Object> metadata,List<String> warnings,List<Artifact> artifacts){}
    static String id(){return UUID.randomUUID().toString();}
    static Block block(String type,Object data){return new Block(id(),type,type,data,Map.of());}
}
