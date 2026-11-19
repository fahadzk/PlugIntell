package com.core.docintel.text2cypher;

import com.core.docintel.neo4j.GraphPolicy;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import java.util.regex.*;

/** Provider-neutral interpretation contract; domain names and Cypher patterns belong to the model/schema. */
final class SemanticPlan {
    record Hints(boolean path,boolean ordered,boolean intermediateOnly,boolean direct,boolean aggregate,boolean descriptive,
                 String betweenStart,String betweenEnd,String journeyStart,String journeyEnd,String descriptiveRequest,String subject){}
    record Part(String id,String request,String kind,boolean supported,List<String> resultColumns,List<String> schemaEvidence,String reason){}
    record Anchor(String role,String mention,String label,String property,String parameter){}
    record Intent(String operation,boolean ordered,boolean intermediateOnly,String direction,String subrouteStart,String subrouteEnd,
                  String journeyStart,String journeyEnd,String pathColumn,List<Part> parts,List<Anchor> anchors,boolean explicit){}
    record Draft(CypherGuard.Plan query,Intent intent){}
    static Hints hints(String question){
        String lower=question.toLowerCase(Locale.ROOT);
        var between=Pattern.compile("(?i)\\bbetween\\s+(.{1,120}?)\\s+and\\s+(.{1,120}?)(?=[,?!;]|\\s+(?:in|on|for|with)\\b|$)").matcher(question);
        String a="",b="";if(between.find()){a=between.group(1).strip();b=between.group(2).strip();}
        var journey=Pattern.compile("(?i)\\bfrom\\s+(.{1,120}?)\\s+to\\s+(.{1,120}?)(?=[,?!;.]|\\s+(?:in|on|for|with|using|via)\\b|$)").matcher(question);
        String from="",to="",subject="";if(journey.find()){
            from=journey.group(1).strip();to=journey.group(2).strip();
            var context=Pattern.compile("(?i)^\\s+(?:on|using|via)\\s+([^,.;?!]{1,120})").matcher(question.substring(journey.end()));
            if(context.find())subject=context.group(1).strip();
        }
        boolean direct=Pattern.compile("\\b(neighbou?rs?|adjacent|directly connected|immediate(?:ly)? next)\\b").matcher(lower).find();
        boolean path=!a.isEmpty()||Pattern.compile("\\b(path|multi.hop|intermediate|travers(?:e|al)|chain)\\b").matcher(lower).find();
        boolean ordered=path||Pattern.compile("\\b(order|ordered|sequence)\\b").matcher(lower).find();
        boolean aggregate=Pattern.compile("\\b(count|how many|total|sum|average|avg|minimum|maximum)\\b").matcher(lower).find();
        var description=Pattern.compile("(?i)\\b(why|explain|suitab(?:le|ility)|benefits?|good choice|recommend)\\b").matcher(question);
        String request=description.find()?question.substring(description.start()):"";
        return new Hints(path,ordered,!a.isEmpty()&&!lower.matches("(?s).*\\b(inclusive|including (?:both |the )?(?:endpoints|ends))\\b.*"),direct&&!path,aggregate,!request.isEmpty(),a,b,from,to,request,subject);
    }
    static Draft parse(JsonNode root,Hints hints){
        if(!root.isObject()||root.size()>(root.has("intent")?3:2))throw new IllegalArgumentException("Unexpected Text-to-Cypher response fields.");
        var query=CypherGuard.validate(GraphPolicy.JSON.valueToTree(Map.of("cypher",root.path("cypher"),"parameters",root.path("parameters"))));
        if(!root.has("intent")){
            List<Part> parts=new ArrayList<>(List.of(new Part("data","Retrieved graph information",hints.path()?"PATH":hints.aggregate()?"AGGREGATION":hints.direct()?"DIRECT":"LOOKUP",true,List.of(),List.of(),"Legacy output has no explicit coverage claims.")));
            if(hints.descriptive())parts.add(new Part("description",hints.descriptiveRequest(),"DESCRIPTION",false,List.of(),List.of(),"No explicit descriptive schema evidence was provided."));
            return new Draft(query,new Intent(hints.path()?"PATH":hints.aggregate()?"AGGREGATION":hints.direct()?"DIRECT":"LOOKUP",hints.ordered(),hints.intermediateOnly(),"BOTH",hints.betweenStart(),hints.betweenEnd(),hints.journeyStart(),hints.journeyEnd(),"",parts,List.of(),false));
        }
        JsonNode value=root.get("intent");if(!value.isObject())throw new IllegalArgumentException("intent must be an object.");
        String operation=choice(value,"operation",Set.of("PATH","DIRECT","AGGREGATION","FILTER","LOOKUP"));
        String direction=choice(value,"direction",Set.of("OUT","IN","BOTH"));
        for(String flag:List.of("ordered","intermediateOnly"))if(!value.path(flag).isBoolean())throw new IllegalArgumentException("Intent flags must be booleans.");
        List<Part> parts=new ArrayList<>();Set<String> ids=new HashSet<>();
        if(!value.path("parts").isArray()||value.path("parts").isEmpty()||value.path("parts").size()>8)throw new IllegalArgumentException("Intent needs 1–8 question parts.");
        for(JsonNode part:value.path("parts")){
            String id=string(part,"id",64);if(!id.matches("[A-Za-z0-9_][A-Za-z0-9_.-]{0,63}")||!ids.add(id))throw new IllegalArgumentException("Question part IDs must be unique nonempty strings of at most 64 letters, digits, underscores, dots or hyphens; numeric strings are allowed.");
            if(!part.path("supported").isBoolean())throw new IllegalArgumentException("Question part supported must be a JSON boolean.");
            parts.add(new Part(id,string(part,"request",500),choice(part,"kind",Set.of("PATH","DIRECT","AGGREGATION","FILTER","LOOKUP","DESCRIPTION")),part.path("supported").asBoolean(),strings(part,"resultColumns"),strings(part,"schemaEvidence"),string(part,"reason",500)));
        }
        List<Anchor> anchors=new ArrayList<>();
        if(!value.path("anchors").isArray()||value.path("anchors").size()>12)throw new IllegalArgumentException("Intent anchors must be a bounded array.");
        for(JsonNode anchor:value.path("anchors"))anchors.add(new Anchor(choice(anchor,"role",Set.of("SUBROUTE_START","SUBROUTE_END","JOURNEY_START","JOURNEY_END","SUBJECT","FILTER")),string(anchor,"mention",200),string(anchor,"label",128),string(anchor,"property",128),string(anchor,"parameter",64)));
        return new Draft(query,new Intent(operation,value.path("ordered").asBoolean(),value.path("intermediateOnly").asBoolean(),direction,string(value,"subrouteStart",200),string(value,"subrouteEnd",200),string(value,"journeyStart",200),string(value,"journeyEnd",200),string(value,"pathColumn",128),List.copyOf(parts),List.copyOf(anchors),true));
    }
    static String string(JsonNode node,String key,int max){if(!node.path(key).isTextual()||node.path(key).asText().length()>max)throw new IllegalArgumentException("Invalid intent field: "+key);return node.path(key).asText().strip();}
    private static String choice(JsonNode node,String key,Set<String> options){String value=string(node,key,32);if(!options.contains(value))throw new IllegalArgumentException("Unsupported intent "+key);return value;}
    private static List<String> strings(JsonNode node,String key){if(!node.path(key).isArray()||node.path(key).size()>16)throw new IllegalArgumentException("Invalid intent list: "+key);List<String> out=new ArrayList<>();for(JsonNode entry:node.path(key)){if(!entry.isTextual()||entry.asText().length()>200)throw new IllegalArgumentException("Invalid intent list value.");out.add(entry.asText());}return List.copyOf(out);}
    private static Map<String,Object> object(Map<String,Object> properties){return Map.of("type","object","additionalProperties",false,"required",new ArrayList<>(properties.keySet()),"properties",properties);}
    private static Map<String,Object> text(){return Map.of("type","string");}
    private static Map<String,Object> array(Object items){return Map.of("type","array","items",items);}
    private static Map<String,Object> enumeration(String... values){return Map.of("type","string","enum",List.of(values));}
    static Map<String,Object> schema(){
        var fields=new LinkedHashMap<String,Object>();fields.put("operation",enumeration("PATH","DIRECT","AGGREGATION","FILTER","LOOKUP"));fields.put("ordered",Map.of("type","boolean"));fields.put("intermediateOnly",Map.of("type","boolean"));fields.put("direction",enumeration("OUT","IN","BOTH"));
        for(String field:List.of("subrouteStart","subrouteEnd","journeyStart","journeyEnd","pathColumn"))fields.put(field,text());
        fields.put("parts",array(object(Map.of("id",text(),"request",text(),"kind",enumeration("PATH","DIRECT","AGGREGATION","FILTER","LOOKUP","DESCRIPTION"),"supported",Map.of("type","boolean"),"resultColumns",array(text()),"schemaEvidence",array(text()),"reason",text()))));
        fields.put("anchors",array(object(Map.of("role",enumeration("SUBROUTE_START","SUBROUTE_END","JOURNEY_START","JOURNEY_END","SUBJECT","FILTER"),"mention",text(),"label",text(),"property",text(),"parameter",text()))));
        var root=new LinkedHashMap<String,Object>((Map<String,Object>)CypherGuard.schema().get("properties"));root.put("intent",object(fields));return object(root);
    }
}
