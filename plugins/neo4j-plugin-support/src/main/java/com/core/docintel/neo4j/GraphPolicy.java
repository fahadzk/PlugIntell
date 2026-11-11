package com.core.docintel.neo4j;

import com.core.docintel.pipeline.api.*;
import com.core.docintel.pipeline.api.KnowledgeGraph.*;
import com.fasterxml.jackson.databind.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.*;

/** Domain-independent validation and identities. Model fields never become physical graph identifiers. */
public final class GraphPolicy {
    public static final ObjectMapper JSON=new ObjectMapper().enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    public final Set<String> entityTypes,relationshipTypes;
    public final JsonNode propertySchemas,constraints;
    public final boolean strict,caseFold;
    public final String identityPolicy,identityProperty;
    public final int maxEntities,maxFacts,maxAliases,maxName,maxProperties,maxQuote;
    public GraphPolicy(Map<String,Object> c) {
        entityTypes=types(text(c,"KG_ENTITY_TYPES",""));relationshipTypes=types(text(c,"KG_RELATIONSHIP_TYPES",""));
        String mode=choice(c,"KG_SCHEMA_MODE","OPEN","OPEN","STRICT");strict=mode.equals("STRICT");
        if(strict&&(entityTypes.isEmpty()||relationshipTypes.isEmpty()))throw new IllegalArgumentException("STRICT requires KG_ENTITY_TYPES and KG_RELATIONSHIP_TYPES.");
        identityPolicy=choice(c,"KG_IDENTITY_POLICY","CHUNK_EVIDENCE","CHUNK_EVIDENCE","PROPERTY");
        identityProperty=text(c,"KG_IDENTITY_PROPERTY","");caseFold=choice(c,"KG_NORMALIZATION","NFKC_CASEFOLD","NFKC_CASEFOLD","NFKC").equals("NFKC_CASEFOLD");
        propertySchemas=parse(text(c,"KG_PROPERTY_SCHEMAS","{}"));constraints=parse(text(c,"KG_RELATIONSHIP_CONSTRAINTS","[]"));
        if(!propertySchemas.isObject()||propertySchemas.size()>32||!constraints.isArray()||constraints.size()>64)throw new IllegalArgumentException("Invalid graph property schema or relationship constraints.");
        propertySchemas.fields().forEachRemaining(e->{identifier(e.getKey());JsonNode schema=e.getValue();if(!schema.isObject())throw new IllegalArgumentException("Property schemas must be objects.");
            for(String field:iterable(schema.fieldNames()))if(!Set.of("type","enum","maxLength","minimum","maximum").contains(field))throw new IllegalArgumentException("Unsupported property schema keyword.");
            if(!Set.of("string","integer","number","boolean").contains(schema.path("type").asText()))throw new IllegalArgumentException("Graph properties support scalar string/integer/number/boolean schemas only.");
            if(schema.has("maxLength")&&(!schema.get("maxLength").isIntegralNumber()||schema.get("maxLength").asInt()<1||schema.get("maxLength").asInt()>2048))throw new IllegalArgumentException("Invalid property maxLength.");
            if(schema.has("enum")&&(!schema.get("enum").isArray()||schema.get("enum").size()>64))throw new IllegalArgumentException("Invalid property enum.");
            for(String bound:List.of("minimum","maximum"))if(schema.has(bound)&&(!schema.get(bound).isNumber()||!Double.isFinite(schema.get(bound).asDouble())))throw new IllegalArgumentException("Invalid numeric property bound.");
        });
        for(JsonNode rule:constraints){fields(rule,Set.of("source","relationship","target"));identifier(rule.path("source").asText());identifier(rule.path("relationship").asText());identifier(rule.path("target").asText());}
        if(identityPolicy.equals("PROPERTY")&&(!propertySchemas.has(identityProperty)||!"string".equals(propertySchemas.path(identityProperty).path("type").asText())))throw new IllegalArgumentException("PROPERTY identity requires a permitted string KG_IDENTITY_PROPERTY schema.");
        if(identityPolicy.equals("PROPERTY")&&Set.of("name","canonicalname","label","title","alias","aliases").contains(identityProperty.toLowerCase(Locale.ROOT)))throw new IllegalArgumentException("A display name or alias cannot be used as an entity identity property.");
        maxEntities=number(c,"KG_MAX_ENTITIES",64,1,256);maxFacts=number(c,"KG_MAX_RELATIONSHIPS",128,1,512);
        maxAliases=number(c,"KG_MAX_ALIASES",8,0,32);maxName=number(c,"KG_MAX_NAME_CHARS",240,1,1000);
        maxProperties=number(c,"KG_MAX_PROPERTIES",16,0,32);maxQuote=number(c,"KG_MAX_EVIDENCE_CHARS",2000,1,8000);
    }
    public Extraction validate(JsonNode root,TextChunk chunk,PluginExecutionContext context,Map<String,Object> provenance) {
        fields(root,Set.of("entities","relationships"));JsonNode entities=root.path("entities"),relationships=root.path("relationships");
        if(!entities.isArray()||!relationships.isArray()||entities.size()>maxEntities||relationships.size()>maxFacts)throw invalid("Invalid extraction counts.");
        Map<String,Entity> byLocal=new LinkedHashMap<>();Set<String> keys=new HashSet<>();
        for(JsonNode e:entities){
            fields(e,Set.of("id","type","name","aliases","properties","evidence"));String local=required(e,"id",64);if(!local.matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,63}"))throw invalid("Invalid response-local ID.");
            String type=semantic(required(e,"type",64),entityTypes);String name=required(e,"name",maxName);
            lookupName(name);
            Evidence quote=evidence(e.path("evidence"),chunk.text());
            if(!literal(quote.quote(),name))throw invalid("Entity name is not supported by its evidence.");
            JsonNode aliases=e.path("aliases");if(!aliases.isArray()||aliases.size()>maxAliases)throw invalid("Invalid aliases.");
            List<String> aliasList=new ArrayList<>();for(JsonNode alias:aliases){if(!alias.isTextual()||alias.asText().isBlank()||alias.asText().length()>maxName||!literal(quote.quote(),alias.asText()))throw invalid("Unsupported alias.");lookupName(alias.asText());aliasList.add(alias.asText());}
            Map<String,Object> properties=properties(e.path("properties"),quote.quote());
            String identity=identityPolicy.equals("PROPERTY")?Objects.toString(properties.get(identityProperty),""):
                    chunk.documentId()+"\n"+chunk.id()+"\n"+normalize(name)+"\n"+quote.start();
            if(identity.isBlank())throw invalid("Entity identity property is missing.");
            if(identityPolicy.equals("PROPERTY")&&normalize(identity).equals(normalize(name)))throw invalid("Entity identity must not be merely its display name.");
            String key=hash(scopeKey(context)+"\nentity\n"+type+"\n"+identity);
            Entity entity=new Entity(local,key,type,name,aliasList,properties,quote);
            if(byLocal.putIfAbsent(local,entity)!=null||!keys.add(key))throw invalid("Duplicate entity identity; combine its evidence in one entity record.");
        }
        List<Fact> facts=new ArrayList<>();Set<String> factKeys=new HashSet<>();
        for(JsonNode r:relationships){
            fields(r,Set.of("source","type","target","properties","evidence"));Entity source=byLocal.get(required(r,"source",64)),target=byLocal.get(required(r,"target",64));
            if(source==null||target==null)throw invalid("Relationship endpoint does not exist.");
            String type=semantic(required(r,"type",64),relationshipTypes);
            if(!constraints.isEmpty()){boolean valid=false;for(JsonNode rule:constraints)if(rule.path("source").asText().equals(source.type())&&rule.path("relationship").asText().equals(type)&&rule.path("target").asText().equals(target.type()))valid=true;if(!valid)throw invalid("Relationship violates configured source/type/target constraints.");}
            Evidence quote=evidence(r.path("evidence"),chunk.text());
            if(!literal(quote.quote(),source.name())||!literal(quote.quote(),target.name()))throw invalid("Relationship evidence must identify both canonical endpoints.");
            Map<String,Object> properties=properties(r.path("properties"),quote.quote());String key=hash(scopeKey(context)+"\nfact\n"+source.key()+"\n"+type+"\n"+target.key());
            if(!factKeys.add(key))throw invalid("Duplicate relationship.");facts.add(new Fact(key,source.key(),type,target.key(),properties,quote));
        }
        return new Extraction(chunk,new ArrayList<>(byLocal.values()),facts,provenance);
    }
    private Map<String,Object> properties(JsonNode properties,String evidence){
        if(!properties.isObject()||properties.size()>maxProperties)throw invalid("Invalid property object.");Map<String,Object> values=new TreeMap<>();
        properties.fields().forEachRemaining(entry->{String key=entry.getKey();JsonNode value=entry.getValue(),schema=propertySchemas.get(key);if(schema==null)throw invalid("Property is not permitted.");
            boolean valid=switch(schema.path("type").asText()){case "string"->value.isTextual()&&value.asText().length()<=schema.path("maxLength").asInt(2048);case "integer"->value.isIntegralNumber()&&value.canConvertToLong();case "number"->value.isNumber()&&Double.isFinite(value.asDouble());case "boolean"->value.isBoolean();default->false;};
            if(!valid)throw invalid("Invalid property type or length.");if(schema.has("enum")){boolean found=false;for(JsonNode allowed:schema.get("enum"))if(allowed.equals(value))found=true;if(!found)throw invalid("Property is outside its enum.");}
            if(value.isNumber()&&(schema.has("minimum")&&value.decimalValue().compareTo(schema.get("minimum").decimalValue())<0||schema.has("maximum")&&value.decimalValue().compareTo(schema.get("maximum").decimalValue())>0))throw invalid("Property is outside numeric bounds.");
            String literal=value.isTextual()?value.asText():value.toString();if(literal.isBlank()||!literal(evidence,literal))throw invalid("Property value is not quoted in its evidence.");
            Object scalar;if(value.isTextual())scalar=value.asText();else if(value.isBoolean())scalar=value.asBoolean();else if(value.isIntegralNumber())scalar=Long.valueOf(value.longValue());else scalar=Double.valueOf(value.doubleValue());values.put(key,scalar);
        });return values;
    }
    private Evidence evidence(JsonNode node,String source){fields(node,Set.of("quote","occurrence"));JsonNode raw=node.path("quote");if(!raw.isTextual()||raw.asText().isBlank()||raw.asText().length()>maxQuote)throw invalid("Invalid evidence quote.");String quote=raw.asText();JsonNode occurrence=node.path("occurrence");
        if(!occurrence.isIntegralNumber()||!occurrence.canConvertToInt()||occurrence.asInt()<0||occurrence.asInt()>1000)throw invalid("Invalid evidence occurrence.");int offset=-1;for(int n=0;n<=occurrence.asInt();n++){offset=source.indexOf(quote,offset+1);if(offset<0)throw invalid("Evidence quote does not match the supplied chunk.");}return new Evidence(quote,offset,offset+quote.length());}
    private String semantic(String type,Set<String> allowed){identifier(type);if(!allowed.isEmpty()&&!allowed.contains(type))throw invalid("Semantic type is not permitted.");return type;}
    public String normalize(String value){String normalized=Normalizer.normalize(value,Normalizer.Form.NFKC).strip().replaceAll("\\s+"," ");return caseFold?normalized.toLowerCase(Locale.ROOT):normalized;}
    private boolean literal(String source,String text){return java.util.regex.Pattern.compile("(?<![\\p{L}\\p{N}\\p{M}_])"+java.util.regex.Pattern.quote(normalize(text))+"(?![\\p{L}\\p{N}\\p{M}_])").matcher(normalize(source)).find();}
    public static String lookupName(String value){String normalized=Normalizer.normalize(value,Normalizer.Form.NFKC).strip().toLowerCase(Locale.ROOT).replaceAll("\\s+"," ");if(normalized.getBytes(StandardCharsets.UTF_8).length>1024)throw invalid("Normalized alias exceeds the 1024-byte index-key budget.");return normalized;}
    public static String scopeKey(PluginExecutionContext c){return hash(c.namespace()+"\n"+c.accessScope());}
    public static String hash(String s){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException("SHA-256 unavailable.");}}
    public static String encode(Object value){try{return JSON.writeValueAsString(value);}catch(Exception e){throw new IllegalArgumentException("Graph value cannot be serialized.");}}
    public static JsonNode parse(String value){try{JsonNode parsed=JSON.readTree(value);if(parsed==null)throw new IllegalArgumentException();return parsed;}catch(Exception e){throw new IllegalArgumentException("Malformed graph configuration JSON.");}}
    public static String text(Map<String,Object> c,String key,String fallback){Object v=c.get(key);String value=v==null?fallback:v.toString();if(value.length()>32000)throw new IllegalArgumentException(key+" exceeds its configuration size limit.");return value;}
    public static int number(Map<String,Object> c,String key,int fallback,int min,int max){try{int v=Integer.parseInt(text(c,key,""+fallback));if(v<min||v>max)throw new NumberFormatException();return v;}catch(NumberFormatException e){throw new IllegalArgumentException(key+" must be between "+min+" and "+max+".");}}
    public static String choice(Map<String,Object> c,String key,String fallback,String... values){String v=text(c,key,fallback);if(!List.of(values).contains(v))throw new IllegalArgumentException("Unsupported "+key+".");return v;}
    public static Set<String> types(String value){Set<String> result=new TreeSet<>();if(!value.isBlank())for(String type:value.split(",")){identifier(type.strip());result.add(type.strip());}if(result.size()>64)throw new IllegalArgumentException("At most 64 semantic types are supported.");return Set.copyOf(result);}
    public static void identifier(String value){if(!value.matches("[A-Za-z][A-Za-z0-9_.:-]{0,63}"))throw invalid("Invalid semantic identifier.");}
    public static void fields(JsonNode node,Set<String> fields){if(!node.isObject()||node.size()!=fields.size())throw invalid("Unexpected or missing structured fields.");node.fieldNames().forEachRemaining(key->{if(!fields.contains(key))throw invalid("Unexpected structured field.");});}
    public static String required(JsonNode node,String field,int length){JsonNode v=node.path(field);if(!v.isTextual()||v.asText().isBlank()||v.asText().length()>length)throw invalid("Invalid structured string field.");return v.asText().strip();}
    private static IllegalArgumentException invalid(String message){return new IllegalArgumentException(message);}
    private static <T> Iterable<T> iterable(Iterator<T> iterator){return ()->iterator;}
    public Map<String,Object> extractionSchema(){
        Map<String,Object> quote=object(Map.of("quote",Map.of("type","string","minLength",1,"maxLength",maxQuote),"occurrence",Map.of("type","integer","minimum",0,"maximum",1000)));
        Map<String,Object> properties=new LinkedHashMap<>();properties.put("type","object");properties.put("properties",JSON.convertValue(propertySchemas,Map.class));properties.put("additionalProperties",false);properties.put("maxProperties",maxProperties);
        var entityProperties=new LinkedHashMap<>(properties);if(identityPolicy.equals("PROPERTY"))entityProperties.put("required",List.of(identityProperty));
        var entity=object(Map.of("id",string(64),"type",typeSchema(entityTypes),"name",string(maxName),"aliases",Map.of("type","array","items",string(maxName),"maxItems",maxAliases),"properties",entityProperties,"evidence",quote));
        var relation=object(Map.of("source",string(64),"type",typeSchema(relationshipTypes),"target",string(64),"properties",properties,"evidence",quote));
        return object(Map.of("entities",Map.of("type","array","items",entity,"maxItems",maxEntities),"relationships",Map.of("type","array","items",relation,"maxItems",maxFacts)));
    }
    public static Map<String,Object> object(Map<String,?> properties){return Map.of("type","object","properties",properties,"required",new ArrayList<>(properties.keySet()),"additionalProperties",false);}
    public static Map<String,Object> string(int max){return Map.of("type","string","minLength",1,"maxLength",max);}
    private static Map<String,Object> typeSchema(Set<String> types){return types.isEmpty()?string(64):Map.of("type","string","enum",types.stream().sorted().toList());}
}
