package com.core.docintel.plugins;

import com.core.docintel.pipeline.api.*;
import com.core.docintel.neo4j.Neo4jConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.neo4j.driver.*;
import java.net.URI;
import java.time.Duration;
import java.util.*;

/** Document-scoped graph upserts. All validation precedes database access. */
public final class Neo4JGraphWriterPlugin extends AbstractPlugin implements ConfigurablePlugin,ManagedPlugin,PreflightPlugin {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final List<String> CONSTRAINTS=List.of(
        "CREATE CONSTRAINT extracted_graph_document IF NOT EXISTS FOR (n:ExtractedGraphDocument) REQUIRE (n.namespace,n.documentId) IS UNIQUE",
        "CREATE CONSTRAINT extracted_graph_entity IF NOT EXISTS FOR (n:ExtractedGraphEntity) REQUIRE (n.namespace,n.documentId,n.id) IS UNIQUE",
        "CREATE CONSTRAINT extracted_graph_chunk IF NOT EXISTS FOR (n:ExtractedGraphChunk) REQUIRE (n.namespace,n.documentId,n.id) IS UNIQUE");
    private Driver driver;private final boolean supplied;private Neo4jConfig config;
    private String namespace="default";private int batchSize=500,timeout=120;private boolean provision=true,ready;
    public Neo4JGraphWriterPlugin(){this(null,PluginMessages.noop());}
    public Neo4JGraphWriterPlugin(PluginMessages messages){this(null,messages);}
    Neo4JGraphWriterPlugin(Driver driver,PluginMessages messages){super(messages);this.driver=driver;supplied=driver!=null;}
    public PluginManifest manifest(){return new PluginManifest("neo4j-graph-writer","Neo4j Graph Writer","1.0.0","1.0",getClass().getName(),"ExtractedGraph","GraphWriteResult");}
    private static String value(Map<String,Object> c,String name,String fallback){return Objects.toString(c.getOrDefault(name,System.getenv().getOrDefault(name,fallback)),"");}
    public void configure(Map<String,Object> c){
        var next=new Neo4jConfig(value(c,"NEO4J_URI","bolt://localhost:7687"),value(c,"NEO4J_USERNAME","neo4j"),value(c,"NEO4J_PASSWORD",""),value(c,"NEO4J_DATABASE","neo4j"),"unused",1);
        try{var uri=URI.create(next.uri());if(uri.getHost()==null||uri.getUserInfo()!=null||uri.getQuery()!=null||uri.getFragment()!=null||!Set.of("bolt","bolt+s","bolt+ssc","neo4j","neo4j+s","neo4j+ssc").contains(uri.getScheme()))throw new IllegalArgumentException();}catch(Exception e){throw new IllegalArgumentException("NEO4J_URI must be a Bolt or Neo4j server URI without embedded credentials.");}
        if(next.password().startsWith("secret://"))throw new IllegalArgumentException("NEO4J_PASSWORD must be resolved by the host.");
        String ns=value(c,"NEO4J_GRAPH_NAMESPACE","default");if(!ns.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}"))throw new IllegalArgumentException("Invalid NEO4J_GRAPH_NAMESPACE.");
        int batch=number(c,"NEO4J_GRAPH_BATCH_SIZE",500,1,10000),seconds=number(c,"NEO4J_GRAPH_TIMEOUT_SECONDS",120,1,600);
        String p=value(c,"NEO4J_GRAPH_PROVISION_SCHEMA","true");if(!Set.of("true","false").contains(p))throw new IllegalArgumentException("NEO4J_GRAPH_PROVISION_SCHEMA must be true or false.");
        if(driver!=null&&!supplied){driver.close();driver=null;}config=next;namespace=ns;batchSize=batch;timeout=seconds;provision=Boolean.parseBoolean(p);ready=false;
    }
    private static int number(Map<String,Object> c,String name,int fallback,int min,int max){int n=Integer.parseInt(value(c,name,""+fallback));if(n<min||n>max)throw new IllegalArgumentException(name+" outside allowed range.");return n;}
    private Driver driver(){if(config==null)configure(Map.of());if(driver==null)driver=GraphDatabase.driver(config.uri(),AuthTokens.basic(config.username(),config.password()),Config.builder().withConnectionTimeout(timeout,java.util.concurrent.TimeUnit.SECONDS).build());return driver;}
    public void preflight(){
        if(ready)return;
        try{driver().verifyConnectivity();try(var session=driver.session(SessionConfig.builder().withDatabase(config.database()).withDefaultAccessMode(AccessMode.WRITE).build())){
            if(provision)for(String query:CONSTRAINTS)session.run(query).consume();
            var constraints=session.run("SHOW CONSTRAINTS YIELD entityType, type, labelsOrTypes, properties RETURN entityType, type, labelsOrTypes, properties").list();
            for(String label:List.of("ExtractedGraphDocument","ExtractedGraphEntity","ExtractedGraphChunk")){
                Set<String> keys=label.equals("ExtractedGraphDocument")?Set.of("namespace","documentId"):Set.of("namespace","documentId","id");
                boolean found=constraints.stream().anyMatch(row->row.get("entityType").asString().equals("NODE")&&Set.of("UNIQUENESS","NODE_PROPERTY_UNIQUENESS","NODE_KEY").contains(row.get("type").asString())&&row.get("labelsOrTypes").asList(Value::asString).equals(List.of(label))&&new HashSet<>(row.get("properties").asList(Value::asString)).equals(keys));
                if(!found)throw new IllegalStateException("Required graph uniqueness constraints are missing. Enable NEO4J_GRAPH_PROVISION_SCHEMA or apply the documented DDL.");
            }
        }ready=true;}catch(RuntimeException e){if(e instanceof IllegalStateException&&e.getMessage().startsWith("Required graph"))throw e;throw databaseError(e);}
    }
    public Object execute(Object input){
        if(!(input instanceof ExtractedGraph graph))throw new IllegalArgumentException("Neo4j Graph Writer expects ExtractedGraph.");
        Plan plan=validate(graph); // Includes incomplete-output checks, before connecting or provisioning.
        preflight();long total=plan.entities.size()+plan.relationships.size()+plan.chunks.size();progress(0,total,"records");
        info("Writing "+plan.entities.size()+" entities, "+plan.relationships.size()+" relationships and "+plan.chunks.size()+" chunks.");
        Map<String,Object> base=new HashMap<>(Map.of("namespace",namespace,"documentId",graph.documentId()));
        base.put("schemaJson",json(graph.schema()));base.put("metadataJson",json(graph.metadata()));
        try(var session=driver.session(SessionConfig.builder().withDatabase(config.database()).withDefaultAccessMode(AccessMode.WRITE).build())){
            session.executeWrite(tx->{
                // Updating the unique document row takes a lock: concurrent writers of this document serialize.
                tx.run("MERGE (d:ExtractedGraphDocument {namespace:$namespace,documentId:$documentId}) SET d.writeRevision=coalesce(d.writeRevision,0)+1,d.schemaJson=$schemaJson,d.metadataJson=$metadataJson,d.updatedAt=datetime()",base).consume();
                writeBatches(tx,"UNWIND $rows AS row MERGE (c:ExtractedGraphChunk {namespace:$namespace,documentId:$documentId,id:row.id}) SET c.sourceName=row.sourceName,c.pageNumber=row.pageNumber,c.ordinal=row.ordinal,c.text=row.text WITH c MATCH (d:ExtractedGraphDocument {namespace:$namespace,documentId:$documentId}) MERGE (d)-[:HAS_CHUNK]->(c)",plan.chunks,base);
                for(var group:groups(plan.entities).entrySet())writeBatches(tx,"UNWIND $rows AS row MERGE (e:ExtractedGraphEntity {namespace:$namespace,documentId:$documentId,id:row.id}) SET e:`"+group.getKey()+"`,e.type=row.type,e.name=row.name,e.aliases=row.aliases,e.confidence=row.confidence,e.propertiesJson=row.propertiesJson,e.mentionsJson=row.mentionsJson,e.chunkIds=row.chunkIds WITH e,row MATCH (d:ExtractedGraphDocument {namespace:$namespace,documentId:$documentId}) MERGE (d)-[:HAS_ENTITY]->(e) WITH e,row UNWIND row.chunkIds AS chunkId MATCH (c:ExtractedGraphChunk {namespace:$namespace,documentId:$documentId,id:chunkId}) MERGE (e)-[:MENTIONED_IN]->(c)",group.getValue(),base);
                for(var group:groups(plan.relationships).entrySet())writeBatches(tx,"UNWIND $rows AS row MATCH (s:ExtractedGraphEntity {namespace:$namespace,documentId:$documentId,id:row.sourceId}),(t:ExtractedGraphEntity {namespace:$namespace,documentId:$documentId,id:row.targetId}) MERGE (s)-[r:`"+group.getKey()+"` {id:row.id}]->(t) SET r.namespace=$namespace,r.documentId=$documentId,r.type=row.type,r.confidence=row.confidence,r.chunkIds=row.chunkIds,r.propertiesJson=row.propertiesJson",group.getValue(),base);
                return null;
            },TransactionConfig.builder().withTimeout(Duration.ofSeconds(timeout)).build());
        }catch(RuntimeException e){throw databaseError(e);}
        progress(total,total,"records");emit("graph.write.completed",Map.of("entitiesStored",plan.entities.size(),"relationshipsStored",plan.relationships.size(),"chunksStored",plan.chunks.size()));
        return new GraphWriteResult(graph.documentId(),namespace,config.database(),plan.entities.size(),plan.relationships.size(),plan.chunks.size());
    }
    private void writeBatches(TransactionContext tx,String query,List<Map<String,Object>> rows,Map<String,Object> base){for(int i=0;i<rows.size();i+=batchSize){if(Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException("Graph write cancelled.");Map<String,Object> params=new HashMap<>(base);params.put("rows",rows.subList(i,Math.min(i+batchSize,rows.size())));tx.run(query,params).consume();}}
    private static Map<String,List<Map<String,Object>>> groups(List<Map<String,Object>> rows){Map<String,List<Map<String,Object>>> groups=new TreeMap<>();for(var row:rows)groups.computeIfAbsent((String)row.get("type"),k->new ArrayList<>()).add(row);return groups;}
    private static IllegalStateException databaseError(RuntimeException e){String code=e instanceof org.neo4j.driver.exceptions.Neo4jException n?n.code():"connection or transaction failure";if(!code.matches("[A-Za-z0-9. ]+"))code="database failure";return new IllegalStateException("Neo4j graph write failed ("+code+"). Check connectivity, credentials, database access and write permissions. No provider details or credentials are included.");}
    public void close(){if(driver!=null&&!supplied)driver.close();if(!supplied)driver=null;ready=false;}

    record Plan(List<Map<String,Object>> entities,List<Map<String,Object>> relationships,List<Map<String,Object>> chunks){}
    static Plan validate(ExtractedGraph graph){
        if(!Boolean.TRUE.equals(graph.metadata().get("complete"))||nonzero(graph.metadata().get("chunks_failed"))||nonzero(graph.metadata().get("chunks_infeasible")))throw new IllegalArgumentException("Refusing to write an incomplete ExtractedGraph. Resolve extraction failures first.");
        text(graph.documentId(),"documentId",512);
        if(graph.entities().size()>100000||graph.relationships().size()>100000||graph.chunks().size()>100000)throw new IllegalArgumentException("Graph exceeds the 100000-record limit per collection.");
        Map<String,Map<String,Object>> entityTypes=definitions(graph.schema().get("entities")),relationTypes=definitions(graph.schema().get("relationships"));
        Set<String> chunkIds=new HashSet<>();List<Map<String,Object>> chunks=new ArrayList<>();
        for(var c:graph.chunks()){text(c.id(),"chunk ID",512);if(!graph.documentId().equals(c.documentId())||!chunkIds.add(c.id()))throw new IllegalArgumentException("Chunk identity mismatch or duplicate ID.");chunks.add(Map.of("id",c.id(),"sourceName",c.sourceName(),"pageNumber",c.pageNumber(),"ordinal",c.ordinal(),"text",c.text()));}
        Map<String,String> entityIds=new HashMap<>();List<Map<String,Object>> entities=new ArrayList<>(),relations=new ArrayList<>();
        for(var e:graph.entities()){
            String id=text(e.get("id"),"entity ID",256),type=identifier(e.get("type"));
            if(!entityTypes.containsKey(type)||entityIds.putIfAbsent(id,type)!=null)throw new IllegalArgumentException("Undeclared entity type or duplicate ID.");
            Map<String,Object> row=new HashMap<>();row.put("id",id);row.put("type",type);row.put("name",text(e.get("name"),"entity name",4096));row.put("aliases",strings(e.getOrDefault("aliases",List.of())));row.put("confidence",confidence(e.get("confidence")));row.put("chunkIds",references(e.get("chunk_ids"),chunkIds));row.put("propertiesJson",json(e.getOrDefault("properties",Map.of())));row.put("mentionsJson",json(e.getOrDefault("mentions",List.of())));entities.add(row);
        }
        Set<String> relationIds=new HashSet<>();
        for(var r:graph.relationships()){
            String id=text(r.get("id"),"relationship ID",256),type=identifier(r.get("type")),source=text(r.get("source_id"),"source ID",256),target=text(r.get("target_id"),"target ID",256);var definition=relationTypes.get(type);
            if(!relationIds.add(id)||definition==null||!Objects.equals(entityIds.get(source),definition.get("head"))||!Objects.equals(entityIds.get(target),definition.get("tail"))||!entityIds.containsKey(source)||!entityIds.containsKey(target)||source.equals(target))throw new IllegalArgumentException("Invalid relationship type, ID or typed endpoints.");
            Map<String,Object> row=new HashMap<>();row.put("id",id);row.put("type",type);row.put("sourceId",source);row.put("targetId",target);row.put("confidence",confidence(r.get("confidence")));row.put("chunkIds",references(r.get("chunk_ids"),chunkIds));row.put("propertiesJson",json(r.getOrDefault("properties",Map.of())));relations.add(row);
        }
        json(graph.schema());json(graph.metadata());return new Plan(entities,relations,chunks);
    }
    private static boolean nonzero(Object value){return value!=null&&(!(value instanceof Number n)||n.doubleValue()!=0);}
    private static Map<String,Map<String,Object>> definitions(Object raw){if(!(raw instanceof List<?> list))throw new IllegalArgumentException("Schema definitions must be arrays.");Map<String,Map<String,Object>> result=new HashMap<>();for(Object item:list){if(!(item instanceof Map<?,?> map))throw new IllegalArgumentException("Invalid schema definition.");String name=identifier(map.get("name"));Map<String,Object> definition=new HashMap<>();map.forEach((k,v)->definition.put(k.toString(),v));if(result.putIfAbsent(name,definition)!=null)throw new IllegalArgumentException("Duplicate schema type.");}return result;}
    private static String identifier(Object value){String name=text(value,"type",64);if(!name.matches("[A-Za-z][A-Za-z0-9_]*")||name.startsWith("ExtractedGraph"))throw new IllegalArgumentException("Invalid or reserved graph type identifier.");return name;}
    private static String text(Object value,String field,int max){if(!(value instanceof String s)||s.isBlank()||s.length()>max)throw new IllegalArgumentException("Invalid "+field+".");return s;}
    private static List<String> strings(Object raw){if(!(raw instanceof Collection<?> list))throw new IllegalArgumentException("Expected string collection.");List<String> values=new ArrayList<>();for(Object item:list)values.add(text(item,"string value",4096));return values;}
    private static List<String> references(Object raw,Set<String> chunks){var refs=strings(raw==null?List.of():raw);if(!chunks.containsAll(refs))throw new IllegalArgumentException("Unknown chunk provenance reference.");return refs;}
    private static Object confidence(Object value){if(value==null)return null;if(!(value instanceof Number n)||!Double.isFinite(n.doubleValue())||n.doubleValue()<0||n.doubleValue()>1)throw new IllegalArgumentException("Invalid confidence.");return ((Number)value).doubleValue();}
    private static String json(Object value){try{return JSON.writeValueAsString(value);}catch(Exception e){throw new IllegalArgumentException("Graph contains non-serializable data.");}}
}
