package com.core.docintel.text2cypher;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;
import java.util.regex.*;

/** Narrow representation repairs, never a query planner or a substitute for validation. */
final class CandidateNormalizer {
    record Result(JsonNode candidate,List<String> changes){}
    private static final String ID="[A-Za-z][A-Za-z0-9_]*";
    static Result normalize(JsonNode original,SchemaView schema){
        if(original==null||!original.isObject()||!original.path("cypher").isTextual()||!original.path("parameters").isArray())return new Result(original,List.of());
        var root=(ObjectNode)original.deepCopy();String query=root.path("cypher").asText();String mask=CypherGuard.mask(query);
        List<String> changes=new ArrayList<>();Map<String,JsonNode> parameters=new LinkedHashMap<>();
        // Malformed or duplicate entries must still fail the strict contract, including unused ones.
        for(JsonNode p:root.path("parameters")){
            String name=p.path("name").asText("");
            if(!p.isObject()||p.size()!=2||!name.matches("[A-Za-z][A-Za-z0-9_]{0,63}")||!p.has("value")||parameters.putIfAbsent(name,p)!=null)return new Result(original,List.of());
        }
        Set<String> used=new LinkedHashSet<>();var refs=Pattern.compile("\\$("+ID+")").matcher(mask);while(refs.find())used.add(refs.group(1));
        // Verify all value shapes before removing unused parameters, so invalid values cannot be hidden.
        {
            String verification="RETURN "+String.join(", ",parameters.keySet().stream().map(n->"$"+n).toList());
            if(parameters.isEmpty())verification="RETURN 1";
            CypherGuard.validate(com.core.docintel.neo4j.GraphPolicy.JSON.valueToTree(Map.of("cypher",verification,"parameters",root.get("parameters"))));
        }
        JsonNode proposed=root.path("intent");
        if(proposed instanceof ObjectNode intent&&intent.path("operation").asText().equals("PATH")){
            String column=intent.path("pathColumn").asText();
            if(column.matches(ID)&&!Pattern.compile("(?i)\\bUNION\\b").matcher(mask).find()){
                var projection=Pattern.compile("(?i)\\bnodes\\s*\\(\\s*("+ID+")\\s*\\)\\s+AS\\s+"+Pattern.quote(column)+"\\b").matcher(mask);
                if(projection.find()&&projection.start()>lastReturn(mask)&&lastReturn(mask)>=0){
                    String path=projection.group(1);
                    if(Pattern.compile("(?i)\\bMATCH\\s+"+Pattern.quote(path)+"\\s*=\\s*\\(").matcher(mask).find()){
                        query=query.substring(0,projection.start())+path+" AS "+column+query.substring(projection.end());
                        root.put("cypher",query);mask=CypherGuard.mask(query);
                        changes.add("Returned the already-bound native path instead of nodes(path) for declared path column "+column+".");
                    }
                }
            }
            if(intent.path("anchors").isArray()&&intent.path("anchors").isEmpty()){
                var anchors=root.arrayNode();
                for(String role:List.of("SUBROUTE_START","SUBROUTE_END")){
                    String mention=intent.path(role.equals("SUBROUTE_START")?"subrouteStart":"subrouteEnd").asText();
                    var matches=new ArrayList<Map<String,String>>();
                    var node=Pattern.compile("\\(\\s*"+ID+"\\s*:\\s*("+ID+")\\s*\\{\\s*("+ID+")\\s*:\\s*\\$("+ID+")\\s*\\}\\s*\\)").matcher(mask);
                    while(node.find()){
                        JsonNode parameter=parameters.get(node.group(3));
                        if(parameter!=null&&used.contains(node.group(3))&&parameter.path("value").isTextual()&&parameter.path("value").asText().equalsIgnoreCase(mention)&&!mention.isBlank()&&schema.has(node.group(1),node.group(2)))
                            matches.add(Map.of("role",role,"mention",mention,"label",node.group(1),"property",node.group(2),"parameter",node.group(3)));
                    }
                    if(matches.size()==1)anchors.add(com.core.docintel.neo4j.GraphPolicy.JSON.valueToTree(matches.getFirst()));
                }
                if(anchors.size()==2){intent.set("anchors",anchors);changes.add("Derived both endpoint anchors from unambiguous schema-backed node predicates and matching intent values.");}
            }
            if(column.matches(ID)&&!Pattern.compile("(?i)\\b(?:UNION|WITH|UNWIND|OPTIONAL)\\b").matcher(mask).find()){
                var missingPath=Pattern.compile("(?i)\\bRETURN\\s+(nodes\\s*\\(\\s*\\$("+ID+")\\s*\\))\\s+AS\\s+"+Pattern.quote(column)+"\\b").matcher(mask);
                boolean endpoints=false;Set<String> roles=new HashSet<>();for(JsonNode anchor:intent.path("anchors"))roles.add(anchor.path("role").asText());endpoints=roles.containsAll(List.of("SUBROUTE_START","SUBROUTE_END"));
                if(endpoints&&missingPath.find()&&!parameters.containsKey(missingPath.group(2))){
                    var assigned=Pattern.compile("(?i)\\bMATCH\\s+("+ID+")\\s*=\\s*\\(").matcher(mask);List<String> bound=new ArrayList<>();while(assigned.find())bound.add(assigned.group(1));
                    String variable=bound.size()==1?bound.getFirst():"generatedPath";
                    if(bound.size()==1||bound.isEmpty()&&!Pattern.compile("\\bgeneratedPath\\b").matcher(mask).find()){
                        query=query.substring(0,missingPath.start(1))+variable+query.substring(missingPath.end(1));root.put("cypher",query);mask=CypherGuard.mask(query);
                        changes.add("Replaced nodes($path) with a native query variable for the declared endpoint path; no path parameter or graph values were fabricated.");
                    }
                }
            }
            // Bind an omitted return-path variable only around a uniquely identified endpoint sub-path.
            if(column.matches(ID)&&!Pattern.compile("(?i)\\b(?:UNION|WITH|UNWIND|OPTIONAL)\\b").matcher(mask).find()){
                var returned=Pattern.compile("(?i)\\bRETURN\\s+("+ID+")\\s+AS\\s+"+Pattern.quote(column)+"\\b").matcher(mask);
                if(returned.find()){
                    String variable=returned.group(1);int returnAt=returned.start();
                    long uses=Pattern.compile("\\b"+Pattern.quote(variable)+"\\b").matcher(mask).results().count();
                    if(uses==1){
                        String node="\\(\\s*("+ID+")\\b[^()]*\\)";
                        var segment=Pattern.compile(node+"\\s*(?:<-|-)\\s*\\[[^\\]]*\\*[^\\]]*\\]\\s*(?:->|-)\\s*"+node).matcher(mask);
                        List<int[]> candidates=new ArrayList<>();
                        while(segment.find())if(segment.end()<=returnAt&&mask.substring(segment.end(),returnAt).isBlank()){
                            int leftEnd=mask.indexOf(')',segment.start())+1;
                            String leftNode=mask.substring(segment.start(),leftEnd),rightNode=mask.substring(mask.lastIndexOf('(',segment.end()),segment.end());
                            if(endpoint(leftNode,intent,"SUBROUTE_START")&&endpoint(rightNode,intent,"SUBROUTE_END")||endpoint(leftNode,intent,"SUBROUTE_END")&&endpoint(rightNode,intent,"SUBROUTE_START"))candidates.add(new int[]{segment.start(),leftEnd});
                        }
                        if(candidates.size()==1){
                            int start=candidates.getFirst()[0],leftEnd=candidates.getFirst()[1];String prefix=mask.substring(0,start).stripTrailing();boolean continueBinding=true;
                            if(Pattern.compile("(?i)\\bMATCH$").matcher(prefix).find())query=query.substring(0,start)+variable+"="+query.substring(start);
                            else if(prefix.endsWith("->")||prefix.endsWith("]-")||prefix.endsWith(","))query=query.substring(0,leftEnd)+" MATCH "+variable+"="+query.substring(start);
                            else continueBinding=false;
                            if(continueBinding){root.put("cypher",query);changes.add("Bound the omitted native path variable "+variable+" to the unique declared endpoint traversal, preserving preceding subject predicates outside the path.");}
                        }
                    }
                }
            }
        }
        used.clear();var finalRefs=Pattern.compile("\\$("+ID+")").matcher(CypherGuard.mask(root.path("cypher").asText()));while(finalRefs.find())used.add(finalRefs.group(1));
        if(parameters.keySet().containsAll(used)){
            var kept=root.arrayNode();List<String> removed=new ArrayList<>();parameters.forEach((name,p)->{if(used.contains(name))kept.add(p);else removed.add(name);});
            if(!removed.isEmpty()){root.set("parameters",kept);changes.add("Removed unused parameter definitions: "+removed+". Intent/journey metadata is retained.");}
        }
        return new Result(root,List.copyOf(changes));
    }
    private static boolean endpoint(String node,JsonNode intent,String role){
        for(JsonNode anchor:intent.path("anchors"))if(anchor.path("role").asText().equals(role)){
            String label=anchor.path("label").asText(),property=anchor.path("property").asText(),parameter=anchor.path("parameter").asText();
            if(label.matches(ID)&&property.matches(ID)&&parameter.matches(ID)&&Pattern.compile(":\\s*"+Pattern.quote(label)+"\\b").matcher(node).find()&&Pattern.compile("\\b"+Pattern.quote(property)+"\\s*:\\s*\\$"+Pattern.quote(parameter)+"\\b").matcher(node).find())return true;
        }
        return false;
    }
    private static int lastReturn(String mask){var matcher=Pattern.compile("(?i)\\bRETURN\\b").matcher(mask);int start=-1;while(matcher.find())start=matcher.start();return start;}
}
