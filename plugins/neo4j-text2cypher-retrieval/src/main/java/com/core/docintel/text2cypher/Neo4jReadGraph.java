package com.core.docintel.text2cypher;

import com.core.docintel.neo4j.*;
import org.neo4j.driver.*;
import org.neo4j.driver.summary.QueryType;
import org.neo4j.driver.types.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.Function;
import static java.nio.charset.StandardCharsets.UTF_8;

/** Does not create indexes, constraints, data, or PlugIntell-specific graph records. */
final class Neo4jReadGraph implements GraphAccess {
    private final Driver driver;private final String database;private final Duration timeout;
    Neo4jReadGraph(Neo4jConfig config,Duration timeout){this(GraphDatabase.driver(config.uri(),AuthTokens.basic(config.username(),config.password()),Config.builder().withConnectionTimeout(timeout.toMillis(),java.util.concurrent.TimeUnit.MILLISECONDS).withMaxTransactionRetryTime(0,java.util.concurrent.TimeUnit.MILLISECONDS).build()),config.database(),timeout);}
    Neo4jReadGraph(Driver driver,String database,Duration timeout){this.driver=driver;this.database=database;this.timeout=timeout;}
    public void verify(){cancelled();driver.verifyConnectivity();read(tx->{tx.run("RETURN 1 AS connected").consume();return null;});}
    private <T>T read(Function<Transaction,T> work){
        cancelled();
        try(var session=driver.session(SessionConfig.builder().withDatabase(database).withDefaultAccessMode(AccessMode.READ).withFetchSize(32).build());
            var tx=session.beginTransaction(TransactionConfig.builder().withTimeout(timeout).build())){
            try{return work.apply(tx);}finally{tx.rollback();}
        }
    }
    public Map<String,Object> schema(int limit){return read(tx->{
        var nodes=metadata(tx,"CALL db.schema.nodeTypeProperties() YIELD nodeLabels,propertyName,propertyTypes RETURN nodeLabels AS labels,propertyName AS property,propertyTypes AS types LIMIT $limit",limit);
        var relationships=metadata(tx,"CALL db.schema.relTypeProperties() YIELD relType,propertyName,propertyTypes RETURN relType AS type,propertyName AS property,propertyTypes AS types LIMIT $limit",limit);
        var patterns=metadata(tx,"MATCH (a)-[r]->(b) WITH a,r,b LIMIT $limit RETURN DISTINCT labels(a) AS sourceLabels,type(r) AS relationship,labels(b) AS targetLabels",limit);
        if(nodes.isEmpty())throw new IllegalStateException("No node schema is visible in NEO4J_DATABASE. Verify the database/account or configure T2C_SCHEMA_JSON explicitly.");
        return Map.of("nodes",nodes,"relationships",relationships,"observedPatterns",patterns,"patternsAreSampled",true);
    });}
    private static List<Map<String,Object>> metadata(Transaction tx,String query,int limit){
        var result=tx.run(query,Map.of("limit",limit+1));List<Map<String,Object>> rows=new ArrayList<>();
        while(result.hasNext()){cancelled();if(rows.size()>=limit)throw new IllegalStateException("Graph schema exceeds T2C_SCHEMA_LIMIT; increase it or supply a focused T2C_SCHEMA_JSON.");rows.add(result.next().asMap());}
        return List.copyOf(rows);
    }
    public Rows query(CypherGuard.Plan plan,int maxRows,int maxBytes){return read(tx->{
        // READ routing is not a permission boundary. Check the database's classification before execution.
        if(tx.run("EXPLAIN "+plan.cypher(),plan.parameters()).consume().queryType()!=QueryType.READ_ONLY)
            throw new IllegalArgumentException("Generated Cypher is not classified READ_ONLY by Neo4j; nothing was executed.");
        var result=tx.run(plan.cypher(),plan.parameters());List<Map<String,Object>> rows=new ArrayList<>();
        Map<String,Map<String,Object>> nodes=new LinkedHashMap<>(),edges=new LinkedHashMap<>();int bytes=2048;boolean truncated=false;
        while(result.hasNext()){
            cancelled();if(rows.size()>=maxRows){truncated=true;break;}
            var encoder=new Encoder();var record=result.next();Map<String,Object> row=new LinkedHashMap<>();for(String key:record.keys())row.put(key,encoder.value(record.get(key),0));
            int cost=GraphPolicy.encode(row).getBytes(UTF_8).length*4+GraphPolicy.encode(encoder.nodes).getBytes(UTF_8).length*2+GraphPolicy.encode(encoder.edges).getBytes(UTF_8).length*2;
            if((long)bytes+cost>maxBytes||nodes.size()+encoder.nodes.size()>256||edges.size()+encoder.edges.size()>512){
                if(rows.isEmpty())throw new IllegalStateException("The first database result exceeds T2C_MAX_RESULT_BYTES or graph display limits. Return selected properties or smaller collections.");
                truncated=true;break;
            }
            bytes+=cost;rows.add(Collections.unmodifiableMap(row));encoder.nodes.forEach((id,node)->nodes.merge(id,node,(old,next)->next.containsKey("properties")?next:old));edges.putAll(encoder.edges);
        }
        return new Rows(List.copyOf(rows),List.copyOf(nodes.values()),List.copyOf(edges.values()),truncated);
    });}
    static void cancelled(){if(Thread.currentThread().isInterrupted())throw new CancellationException("Text-to-Cypher retrieval cancelled.");}
    public void close(){driver.close();}

    static final class Encoder {
        final Map<String,Map<String,Object>> nodes=new LinkedHashMap<>(),edges=new LinkedHashMap<>();
        Object value(Value value,int depth){
            if(depth>8)throw new IllegalStateException("Database result nesting exceeds eight levels; return selected scalar properties.");
            if(value.isNull())return null;
            Object object=value.asObject();
            if(object instanceof Node node)return node(node,depth+1);
            if(object instanceof Relationship relation)return edge(relation,depth+1);
            if(object instanceof Path path){List<Object> ns=new ArrayList<>(),rs=new ArrayList<>();for(Node n:path.nodes())ns.add(node(n,depth+1));for(Relationship r:path.relationships())rs.add(edge(r,depth+1));return Map.of("nodes",ns,"relationships",rs);}
            if(object instanceof List<?>){if(value.size()>512)throw new IllegalStateException("Database result list exceeds 512 entries; narrow the generated query.");List<Object> out=new ArrayList<>();for(Value entry:value.values())out.add(value(entry,depth+1));return out;}
            if(object instanceof Map<?,?>){if(value.size()>128)throw new IllegalStateException("Database result map exceeds 128 fields.");Map<String,Object> out=new LinkedHashMap<>();for(String key:value.keys())out.put(key,value(value.get(key),depth+1));return out;}
            if(object instanceof Number number){if(!Double.isFinite(number.doubleValue()))throw new IllegalStateException("Non-finite database number cannot be serialized.");return number;}
            if(object instanceof Boolean)return object;
            String text=object instanceof byte[] data?Base64.getEncoder().encodeToString(data):object.toString();
            if(text.length()>16000)throw new IllegalStateException("Database result value exceeds 16000 characters; return selected properties.");return text;
        }
        private Map<String,Object> properties(Entity entity,int depth){
            Map<String,Object> props=new LinkedHashMap<>();int count=0;for(String key:entity.keys()){if(++count>128)throw new IllegalStateException("Graph entity has too many properties; return selected properties.");props.put(key,value(entity.get(key),depth+1));}return props;
        }
        private Map<String,Object> node(Node node,int depth){
            List<String> labels=new ArrayList<>();node.labels().forEach(labels::add);var props=properties(node,depth);
            var out=Map.<String,Object>of("id","n:"+node.elementId(),"labels",labels,"label",Objects.toString(props.getOrDefault("name",labels.isEmpty()?"Node":labels.getFirst())),"properties",props);
            nodes.put("n:"+node.elementId(),out);return out;
        }
        private Map<String,Object> edge(Relationship relation,int depth){
            String source="n:"+relation.startNodeElementId(),target="n:"+relation.endNodeElementId();
            nodes.putIfAbsent(source,Map.of("id",source,"label","Node"));nodes.putIfAbsent(target,Map.of("id",target,"label","Node"));
            var out=Map.<String,Object>of("id","r:"+relation.elementId(),"source",source,"target",target,"label",relation.type(),"properties",properties(relation,depth));edges.put("r:"+relation.elementId(),out);return out;
        }
    }
}
