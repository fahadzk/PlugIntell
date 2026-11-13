package com.core.docintel.text2cypher;

import com.core.docintel.neo4j.GraphPolicy;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

/** Deliberately limited read-query dialect; Neo4j EXPLAIN and a read-only account remain required. */
final class CypherGuard {
    record Plan(String cypher,Map<String,Object> parameters){}
    private static final Set<String> FORBIDDEN=Set.of("CALL","LOAD","USE","CREATE","MERGE","SET","DELETE","DETACH","REMOVE","DROP","ALTER","RENAME","GRANT","DENY","REVOKE","FOREACH","INSERT","FINISH","SHOW","TERMINATE","START","STOP","CYPHER","EXPLAIN","PROFILE","TRANSACTIONS","TRANSACTION");
    static Plan validate(JsonNode response){
        if(!response.isObject()||response.size()!=2||!response.path("cypher").isTextual()||!response.path("parameters").isArray())throw invalid("Expected cypher and parameters fields.");
        String query=response.path("cypher").asText().strip();
        if(query.isEmpty()||query.length()>16000||response.path("parameters").size()>32)throw invalid("Query or parameter count exceeds the allowed limit.");
        Map<String,Object> parameters=new LinkedHashMap<>();
        for(JsonNode item:response.path("parameters")){
            if(!item.isObject()||item.size()!=2||!item.path("name").isTextual()||!item.has("value"))throw invalid("Invalid parameter definition.");
            String name=item.path("name").asText();if(!name.matches("[A-Za-z][A-Za-z0-9_]{0,63}")||parameters.containsKey(name))throw invalid("Invalid or duplicate parameter name.");
            JsonNode value=item.get("value");Object typed;
            if(value.isNull())typed=null;
            else if(value.isTextual()&&value.asText().length()<=2048)typed=value.asText();
            else if(value.isBoolean())typed=value.asBoolean();
            else if(value.isIntegralNumber()&&value.canConvertToLong())typed=value.asLong();
            else if(value.isFloatingPointNumber()&&Double.isFinite(value.asDouble()))typed=value.asDouble();
            else if(value.isArray()&&value.size()<=64){List<String> values=new ArrayList<>();for(JsonNode entry:value){if(!entry.isTextual()||entry.asText().length()>2048)throw invalid("Only bounded string arrays are supported as list parameters.");values.add(entry.asText());}typed=values;}
            else throw invalid("Unsupported or oversized parameter value.");
            parameters.put(name,typed);
        }
        String mask=mask(query);List<String> tokens=new ArrayList<>();var words=java.util.regex.Pattern.compile("[A-Za-z_][A-Za-z0-9_]*").matcher(mask);
        while(words.find()){String token=words.group().toUpperCase(Locale.ROOT);tokens.add(token);if(FORBIDDEN.contains(token))throw invalid("Generated query uses a forbidden clause or procedure.");}
        if(tokens.isEmpty()||!Set.of("MATCH","OPTIONAL","WITH","RETURN","UNWIND").contains(tokens.getFirst())||!tokens.contains("RETURN"))throw invalid("Generated query must be a single read query ending in returned data.");
        if(mask.indexOf(';')>=0||mask.indexOf('\\')>=0)throw invalid("Multiple statements and unquoted escapes are not supported.");
        Set<String> referenced=new HashSet<>();var names=java.util.regex.Pattern.compile("\\$([A-Za-z][A-Za-z0-9_]*)").matcher(mask);while(names.find())referenced.add(names.group(1));
        if(!referenced.equals(parameters.keySet()))throw invalid("Query parameters do not match the supplied parameter list.");
        return new Plan(query,Collections.unmodifiableMap(parameters));
    }
    /** Remove quoted strings, escaped identifiers and comments without joining adjacent tokens. */
    private static String mask(String text){
        StringBuilder result=new StringBuilder(text);int i=0;
        while(i<text.length()){
            char c=text.charAt(i);int start=i;
            if(c=='\''||c=='"'||c=='`'){
                char quote=c;i++;boolean closed=false;
                while(i<text.length()){
                    char next=text.charAt(i++);
                    if(next=='\\'&&quote!='`'){if(i>=text.length())throw invalid("Unterminated string.");i++;}
                    else if(next==quote){if(i<text.length()&&text.charAt(i)==quote)i++;else{closed=true;break;}}
                }
                if(!closed)throw invalid("Unterminated quoted value or identifier.");
            }else if(c=='/'&&i+1<text.length()&&text.charAt(i+1)=='/'){
                i+=2;while(i<text.length()&&text.charAt(i)!='\n'&&text.charAt(i)!='\r')i++;
            }else if(c=='/'&&i+1<text.length()&&text.charAt(i+1)=='*'){
                int end=text.indexOf("*/",i+2);if(end<0)throw invalid("Unterminated comment.");i=end+2;
            }else{i++;continue;}
            for(int j=start;j<i;j++)result.setCharAt(j,' ');
        }
        return result.toString();
    }
    static Map<String,Object> schema(){
        return Map.of("type","object","additionalProperties",false,"required",List.of("cypher","parameters"),"properties",Map.of(
            "cypher",Map.of("type","string"),"parameters",Map.of("type","array","items",Map.of("type","object","additionalProperties",false,"required",List.of("name","value"),"properties",Map.of(
                "name",Map.of("type","string"),"value",Map.of("anyOf",List.of(Map.of("type",List.of("string","number","boolean","null")),Map.of("type","array","items",Map.of("type","string")))))))));
    }
    private static IllegalArgumentException invalid(String reason){return new IllegalArgumentException("Text-to-Cypher validation failed: "+reason);}
}
