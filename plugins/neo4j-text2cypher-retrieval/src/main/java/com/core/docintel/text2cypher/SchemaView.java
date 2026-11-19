package com.core.docintel.text2cypher;
import com.core.docintel.neo4j.GraphPolicy;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

/** Normalizes discovered/manual schemas; schema descriptions are guidance, never stored facts. */
final class SchemaView {
    static Map<String,Object> compact(Map<String,Object> schema){
        Map<String,Object> out=new LinkedHashMap<>(schema);
        for(String lane:List.of("nodes","relationships"))if(schema.get(lane) instanceof List<?> rows&&!rows.isEmpty()&&rows.getFirst() instanceof Map<?,?> first&&first.containsKey("property")){
            Map<String,Map<String,Object>> groups=new LinkedHashMap<>();
            for(Object entry:rows)if(entry instanceof Map<?,?> row){
                Object identity=lane.equals("nodes")?row.get("labels"):row.get("type");String key=GraphPolicy.encode(identity);
                var group=groups.computeIfAbsent(key,k->{Map<String,Object> value=new LinkedHashMap<>();value.put(lane.equals("nodes")?"labels":"type",identity);value.put("properties",new LinkedHashMap<String,Object>());return value;});
                if(row.get("property") instanceof String property)((Map<String,Object>)group.get("properties")).put(property,row.getOrDefault("types",null));
            }
            out.put(lane,List.copyOf(groups.values()));
        }
        return out;
    }
    private final Map<String,Set<String>> properties=new LinkedHashMap<>();
    private final Set<String> identity=new HashSet<>();
    private final Set<String> relationshipTypes=new HashSet<>();
    SchemaView(Map<String,Object> schema){
        JsonNode root=GraphPolicy.JSON.valueToTree(schema);
        for(JsonNode node:root.path("nodes")){
            List<String> labels=new ArrayList<>();for(JsonNode label:node.path("labels"))labels.add(label.asText());
            if(node.path("label").isTextual())labels.add(node.path("label").asText());
            for(String label:labels){
                if(node.path("property").isTextual())add(label,node.path("property").asText());
                if(node.path("properties").isObject())node.path("properties").fieldNames().forEachRemaining(p->add(label,p));
                else for(JsonNode p:node.path("properties"))if(p.path("name").isTextual())add(label,p.path("name").asText());
            }
        }
        for(JsonNode rel:root.path("relationships")){
            if(rel.isTextual()){relationshipTypes.add(rel.asText());continue;}
            String type=rel.path("type").asText().replaceFirst("^:","").replace("`","");
            relationshipTypes.add(type);
            if(rel.path("property").isTextual())add(type,rel.path("property").asText());
            if(rel.path("properties").isObject())rel.path("properties").fieldNames().forEachRemaining(p->add(type,p));
        }
        for(JsonNode constraint:root.path("constraints"))if(constraint.path("type").asText().contains("UNIQUE")||constraint.path("type").asText().contains("UNIQUENESS")||constraint.path("type").asText().contains("KEY"))
            for(JsonNode label:constraint.path("labelsOrTypes"))for(JsonNode property:constraint.path("properties"))identity.add(label.asText()+"."+property.asText());
    }
    private void add(String type,String property){if(!type.isBlank()&&!property.isBlank())properties.computeIfAbsent(type,k->new LinkedHashSet<>()).add(property);}
    boolean has(String type,String property){return properties.getOrDefault(type,Set.of()).contains(property);}
    boolean hasEvidence(String evidence){int dot=evidence.indexOf('.');return dot>0?has(evidence.substring(0,dot),evidence.substring(dot+1)):properties.containsKey(evidence)||relationshipTypes.contains(evidence);}
    boolean descriptiveEvidence(List<String> evidence){
        // Identifier/display-name fields alone cannot establish qualitative suitability or explanations.
        Set<String> identifiers=Set.of("id","key","uuid","name","label","title","code","identifier","sequence","position","ordinal","index","order","origin","destination","source","target","start","end","from","to","head","tail");
        return evidence.stream().anyMatch(e->e.indexOf('.')>0&&hasEvidence(e)&&!identity.contains(e)&&!identifiers.contains(e.substring(e.indexOf('.')+1).toLowerCase(Locale.ROOT)));
    }
    boolean anyDescriptiveEvidence(){return properties.entrySet().stream().anyMatch(e->descriptiveEvidence(e.getValue().stream().map(p->e.getKey()+"."+p).toList()));}
    List<String> descriptiveProperties(){return properties.entrySet().stream().flatMap(e->e.getValue().stream().map(p->e.getKey()+"."+p)).filter(p->descriptiveEvidence(List.of(p))).sorted().limit(16).toList();}
    List<Map<String,Object>> resolve(GraphAccess graph,SemanticPlan.Intent intent){
        List<Map<String,Object>> found=new ArrayList<>();Set<String> seen=new HashSet<>();
        for(var anchor:intent.anchors()){
            if(!has(anchor.label(),anchor.property())||anchor.mention().isBlank()||!seen.add(anchor.label()+"."+anchor.property()+"\n"+anchor.mention()))continue;
            String property="n."+quote(anchor.property());
            String query="MATCH (n:"+quote(anchor.label())+") WHERE toLower(trim(toString("+property+"))) CONTAINS toLower(trim($mention)) RETURN elementId(n) AS elementId, "+property+" AS value, CASE WHEN toLower(trim(toString("+property+")))=toLower(trim($mention)) THEN 0 ELSE 1 END AS rank ORDER BY rank, value LIMIT 6";
            var candidates=graph.query(new CypherGuard.Plan(query,Map.of("mention",anchor.mention())),6,32768);
            List<Map<String,Object>> values=new ArrayList<>();
            for(var row:candidates.rows())if(row.get("elementId") instanceof String id&&row.get("value") instanceof String name)values.add(Map.of("elementId",id,"value",name));
            found.add(Map.of("role",anchor.role(),"mention",anchor.mention(),"label",anchor.label(),"property",anchor.property(),"parameter",anchor.parameter(),"candidates",values));
        }
        return List.copyOf(found);
    }
    static CypherGuard.Plan bind(CypherGuard.Plan query,List<Map<String,Object>> resolutions){
        Map<String,Object> parameters=new LinkedHashMap<>(query.parameters());
        for(var resolution:resolutions){var candidates=(List<?>)resolution.get("candidates");String parameter=(String)resolution.get("parameter");
            if(candidates.size()==1&&parameters.get(parameter) instanceof String current&&matches(current,resolution.get("mention"))){var candidate=(Map<?,?>)candidates.getFirst();parameters.put(parameter,candidate.get("value"));}
        }
        return new CypherGuard.Plan(query.cypher(),Collections.unmodifiableMap(parameters));
    }
    static boolean matches(Object left,Object right){String a=normalize(left),b=normalize(right);return !a.isEmpty()&&!b.isEmpty()&&(a.equals(b)||a.startsWith(b)||b.startsWith(a));}
    private static String normalize(Object value){return Objects.toString(value,"").toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]","");}
    private static String quote(String name){return "`"+name.replace("`","``")+"`";}
}
