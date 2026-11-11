package com.core.docintel.neo4j;
import com.fasterxml.jackson.databind.JsonNode;
import com.core.docintel.neo4j.KnowledgeGraphStore.*;
import java.util.*;

/** A model can request semantic intent only, never physical queries, scope or server limits. */
public final class GraphPlans {
    private GraphPlans(){}
    public static Map<String,Object> schema(){return GraphPolicy.object(Map.of(
            "entities",array(16,240),"aliases",array(16,240),"semanticText",GraphPolicy.string(4000),
            "entityTypes",array(64,64),"relationshipTypes",array(64,64),"direction",Map.of("type","string","enum",List.of("OUT","IN","BOTH")),
            "hops",Map.of("type","integer","minimum",0,"maximum",3)));}
    private static Map<String,Object> array(int count,int length){return Map.of("type","array","items",GraphPolicy.string(length),"maxItems",count);}
    public static Plan validate(JsonNode value,Schema available,Set<String> allowedEntities,Set<String> allowedRelations,int maxHops,String direction){
        GraphPolicy.fields(value,Set.of("entities","aliases","semanticText","entityTypes","relationshipTypes","direction","hops"));
        List<String> entities=strings(value.path("entities"),16,240),aliases=strings(value.path("aliases"),16,240);
        Set<String> e=Set.copyOf(strings(value.path("entityTypes"),64,64)),r=Set.copyOf(strings(value.path("relationshipTypes"),64,64));
        if(!available.entityTypes().containsAll(e)||!available.relationshipTypes().containsAll(r))throw new IllegalArgumentException("Query plan used types outside the available graph schema.");
        if(!allowedEntities.isEmpty()&&!allowedEntities.containsAll(e)||!allowedRelations.isEmpty()&&!allowedRelations.containsAll(r))throw new IllegalArgumentException("Query plan used disallowed semantic types.");
        String requested=GraphPolicy.required(value,"direction",4);if(!Set.of("OUT","IN","BOTH").contains(requested))throw new IllegalArgumentException("Invalid query-plan direction.");
        JsonNode hops=value.path("hops");if(!hops.isIntegralNumber()||!hops.canConvertToInt()||hops.intValue()<0||hops.intValue()>maxHops)throw new IllegalArgumentException("Query plan exceeded its hop limit.");
        // Host configuration is authoritative; a restrictive direction cannot be widened by the planner.
        if(!direction.equals("BOTH")&&!requested.equals(direction))throw new IllegalArgumentException("Query plan exceeded its configured traversal direction.");
        return new Plan(entities,aliases,GraphPolicy.required(value,"semanticText",4000),e.isEmpty()?allowedEntities:e,r.isEmpty()?allowedRelations:r,requested,hops.intValue());
    }
    public static Plan lexical(String question,Set<String> entities,Set<String> relations,String direction,int hops){return new Plan(List.of(),List.of(),question,entities,relations,direction,hops);}
    private static List<String> strings(JsonNode value,int count,int length){if(!value.isArray()||value.size()>count)throw new IllegalArgumentException("Invalid bounded query-plan list.");List<String> result=new ArrayList<>();for(JsonNode item:value){if(!item.isTextual()||item.asText().isBlank()||item.asText().length()>length)throw new IllegalArgumentException("Invalid query-plan term.");result.add(item.asText().strip());}return List.copyOf(result);}
}
