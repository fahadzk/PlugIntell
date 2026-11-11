package com.core.docintel.neo4j;
import com.core.docintel.pipeline.api.*;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.*;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.*;
import java.util.function.Function;
import static org.junit.jupiter.api.Assertions.*;

class GraphRepositoryReadOnlyTest {
    @Test void preflightRecognizesCypher5And25NodeUniquenessWithoutWriting(){
        for(String type:List.of("UNIQUENESS","NODE_PROPERTY_UNIQUENESS","NODE_KEY")){
            try(var repository=preflightRepository(type,"NODE","key",false)){
                assertDoesNotThrow(()->repository.graphPreflight(false,"plugintell_kg_entities",Duration.ofSeconds(1)));
            }
        }
    }
    @Test void preflightRejectsRelationshipWrongPropertyAndMissingConstraintsWithRemediation(){
        for(var fixture:List.of(List.of("RELATIONSHIP_PROPERTY_UNIQUENESS","RELATIONSHIP","key"),List.of("NODE_PROPERTY_UNIQUENESS","NODE","other"),List.of("NODE_PROPERTY_EXISTENCE","NODE","key"))){
            try(var repository=preflightRepository(fixture.get(0),fixture.get(1),fixture.get(2),false)){
                var error=assertThrows(IllegalStateException.class,()->repository.graphPreflight(false,"plugintell_kg_entities",Duration.ofSeconds(1)));
                assertTrue(error.getMessage().contains("KgEntity.key"));assertTrue(error.getMessage().contains("KG_PROVISION_SCHEMA=true"));
            }
        }
        try(var repository=preflightRepository("NODE_PROPERTY_UNIQUENESS","NODE","key",true)){
            var error=assertThrows(IllegalStateException.class,()->repository.graphPreflight(false,"plugintell_kg_entities",Duration.ofSeconds(1)));
            assertTrue(error.getMessage().contains("missing: KgRevision.key."));assertFalse(error.getMessage().contains("missing: KgEntity.key"));
        }
    }
    private static Neo4jChunkRepository preflightRepository(String type,String entityType,String property,boolean omitRevision){
        return preflightRepository(type,entityType,property,omitRevision,rows->rows);
    }
    @Test void preflightAcceptsEquivalentOnlineRangeIndexesWithDifferentNames(){
        try(var repository=preflightRepository("NODE_PROPERTY_UNIQUENESS","NODE","key",false,rows->rows.stream().map(row->{var copy=new HashMap<>(row);copy.put("name","custom_"+row.get("name"));return (Map<String,Object>)copy;}).toList())){
            assertDoesNotThrow(()->repository.graphPreflight(false,"plugintell_kg_entities",Duration.ofSeconds(1)));
        }
    }
    @Test void preflightExplainsMissingPopulatingFailedAndIncorrectLookupIndexes(){
        String name="plugintell_kg_evidence_subject";
        for(String state:List.of("MISSING","POPULATING","FAILED","WRONG_DEFINITION")){
            try(var repository=preflightRepository("UNIQUENESS","NODE","key",false,rows->rows.stream()
                .filter(row->!state.equals("MISSING")||!row.get("name").equals(name)).map(row->{
                    var copy=new HashMap<>(row);
                    if(copy.get("name").equals(name)){if(state.equals("WRONG_DEFINITION"))copy.put("labelsOrTypes",List.of("Unrelated"));else copy.put("state",state);}
                    return (Map<String,Object>)copy;
                }).toList())){
                var error=assertThrows(IllegalStateException.class,()->repository.graphPreflight(false,"plugintell_kg_entities",Duration.ofSeconds(1)));
                assertTrue(error.getMessage().contains(name+"="+state),error.getMessage());assertTrue(error.getMessage().contains("CALL db.awaitIndexes(60)"));
            }
        }
        for(String field:List.of("entityType","type","properties")){
            try(var repository=preflightRepository("UNIQUENESS","NODE","key",false,rows->rows.stream().map(row->{
                var copy=new HashMap<>(row);if(copy.get("name").equals(name))copy.put(field,field.equals("properties")?List.of("key","subject","scopeKey"):field.equals("type")?"FULLTEXT":"RELATIONSHIP");return (Map<String,Object>)copy;
            }).toList())){
                var error=assertThrows(IllegalStateException.class,()->repository.graphPreflight(false,"plugintell_kg_entities",Duration.ofSeconds(1)));
                assertTrue(error.getMessage().contains(name+"=WRONG_DEFINITION"),error.getMessage());
            }
        }
    }
    private static Neo4jChunkRepository preflightRepository(String type,String entityType,String property,boolean omitRevision,java.util.function.UnaryOperator<List<Map<String,Object>>> transformIndexes){
        TransactionContext tx=proxy(TransactionContext.class,(p,m,a)->{
            String query=a[0].toString();
            if(query.startsWith("SHOW CONSTRAINTS"))return result(List.of("KgEntity","KgFact","KgEvidence","KgChunk","KgAlias","KgRevision").stream()
                .filter(label->!omitRevision||!label.equals("KgRevision"))
                .map(label->Map.<String,Object>of("type",type,"entityType",entityType,"labelsOrTypes",List.of(label),"properties",List.of(property))).toList());
            if(query.startsWith("SHOW INDEXES")&&query.contains("WHERE name=$name"))return result(List.of(Map.of("state","ONLINE","type","FULLTEXT","labelsOrTypes",List.of("KgEntity"),"properties",List.of("scopeKey","name","aliases"))));
            if(query.startsWith("SHOW INDEXES"))return result(transformIndexes.apply(Map.of("plugintell_kg_entity_scope",List.of("scopeKey"),"plugintell_kg_alias_lookup",List.of("scopeKey","normalized"),"plugintell_kg_evidence_chunk",List.of("chunkKey"),"plugintell_kg_evidence_subject",List.of("scopeKey","subject","key")).entrySet().stream()
                .map(e->Map.<String,Object>of("name",e.getKey(),"state","ONLINE","properties",e.getValue(),"entityType","NODE","type","RANGE","labelsOrTypes",List.of(e.getKey().contains("entity_scope")?"KgEntity":e.getKey().contains("alias_lookup")?"KgAlias":"KgEvidence"))).toList()));
            throw new AssertionError("Unexpected schema query: "+query);
        });
        Session session=proxy(Session.class,(p,m,a)->{if(m.getName().equals("executeRead"))return ((TransactionCallback<?>)a[0]).execute(tx);if(m.getName().equals("close"))return null;throw new AssertionError("Schema verification attempted "+m.getName());});
        Driver driver=proxy(Driver.class,(p,m,a)->{if(m.getName().equals("session")){assertEquals(AccessMode.READ,((SessionConfig)a[0]).defaultAccessMode());return session;}if(m.getName().equals("close")||m.getName().equals("verifyConnectivity"))return null;throw new AssertionError(m.getName());});
        return new Neo4jChunkRepository(new Neo4jConfig("bolt://localhost:1","user","secret","neo4j","unused",1),driver);
    }
    @Test void elapsedReadDeadlineStopsBeforeAnyQuery(){
        Session session=proxy(Session.class,(p,m,a)->{if(m.getName().equals("executeRead")){Thread.sleep(25);return ((TransactionCallback<?>)a[0]).execute(null);}if(m.getName().equals("close"))return null;throw new AssertionError("Unexpected database work after timeout.");});
        Driver driver=proxy(Driver.class,(p,m,a)->{if(m.getName().equals("session"))return session;if(m.getName().equals("close"))return null;throw new AssertionError(m.getName());});
        try(var repository=new Neo4jChunkRepository(new Neo4jConfig("bolt://localhost:1","u","secret","neo4j","unused",1),driver)){
            var limits=new KnowledgeGraphStore.Limits(10,5,10,20,10,5,5,20000,Duration.ofMillis(1),"plugintell_kg_entities",60);
            var plan=new KnowledgeGraphStore.Plan(List.of("Aster"),List.of(),"Aster",Set.of(),Set.of(),"OUT",1);
            assertThrows(IllegalStateException.class,()->repository.retrieve(new SearchRequest("Aster",true),plan,GraphPolicyTest.SCOPE,limits,List.of()));
        }
    }
    @SuppressWarnings("unchecked") static <T>T proxy(Class<T> type,java.lang.reflect.InvocationHandler handler){return (T)Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},handler);}
    static org.neo4j.driver.Record record(Map<String,Object> row){return proxy(org.neo4j.driver.Record.class,(p,m,a)->{if(m.getName().equals("get"))return Values.value(row.get(a[0]));if(m.getName().equals("keys"))return new ArrayList<>(row.keySet());throw new UnsupportedOperationException(m.getName());});}
    static Result result(List<Map<String,Object>> rows){return proxy(Result.class,(p,m,a)->{
        var records=rows.stream().map(GraphRepositoryReadOnlyTest::record).toList();
        if(m.getName().equals("list")){if(a==null||a.length==0)return records;@SuppressWarnings("unchecked")var map=(Function<org.neo4j.driver.Record,Object>)a[0];return records.stream().map(map).toList();}
        if(m.getName().equals("single"))return records.getFirst();throw new UnsupportedOperationException(m.getName());
    });}
    @Test void extractionUsesOnlyApplicationPhysicalSchemaAndParameterizedModelValues(){
        List<String> queries=new ArrayList<>();List<Map<String,Object>> parameters=new ArrayList<>();
        var counters=proxy(org.neo4j.driver.summary.SummaryCounters.class,(p,m,a)->m.getName().equals("nodesCreated")?1:0);
        var summary=proxy(org.neo4j.driver.summary.ResultSummary.class,(p,m,a)->{if(m.getName().equals("counters"))return counters;throw new UnsupportedOperationException(m.getName());});
        TransactionContext tx=proxy(TransactionContext.class,(p,m,a)->{
            String query=a[0].toString();queries.add(query);@SuppressWarnings("unchecked")Map<String,Object> values=(Map<String,Object>)a[1];parameters.add(values);
            if(query.startsWith("MERGE (r:KgRevision")||query.startsWith("MATCH (r:KgRevision"))return result(List.of(Map.of("value",1)));
            if(query.startsWith("MATCH (c:KgChunk")&&query.contains("RETURN c.revision"))return result(List.of());
            if(query.contains("RETURN e.subject AS subject"))return result(List.of());
            if(query.contains("RETURN size(names) AS count"))return result(List.of(Map.of("count",2)));
            return proxy(Result.class,(r,method,args)->{if(method.getName().equals("consume"))return summary;throw new UnsupportedOperationException(method.getName());});
        });
        Session session=proxy(Session.class,(p,m,a)->{if(m.getName().equals("executeWrite"))return ((TransactionCallback<?>)a[0]).execute(tx);if(m.getName().equals("close"))return null;throw new UnsupportedOperationException(m.getName());});
        Driver driver=proxy(Driver.class,(p,m,a)->{if(m.getName().equals("session")){assertEquals(AccessMode.WRITE,((SessionConfig)a[0]).defaultAccessMode());return session;}if(m.getName().equals("close"))return null;throw new UnsupportedOperationException(m.getName());});
        var root=GraphPolicyTest.extraction();((com.fasterxml.jackson.databind.node.ObjectNode)root.path("entities").get(0)).put("type","ModelSuppliedType");((com.fasterxml.jackson.databind.node.ObjectNode)root.path("entities").get(1)).put("type","ModelSuppliedType");((com.fasterxml.jackson.databind.node.ObjectNode)root.path("relationships").get(0)).put("type","MODEL_SUPPLIED_RELATION");
        var extraction=new GraphPolicy(GraphPolicyTest.propertyConfig()).validate(root,GraphPolicyTest.chunk("one"),GraphPolicyTest.SCOPE,Map.of());
        try(var repository=new Neo4jChunkRepository(new Neo4jConfig("bolt://localhost:1","user","secret","neo4j","unused",1),driver)){
            var result=repository.replace(extraction,GraphPolicyTest.SCOPE,Duration.ofSeconds(1));assertEquals(2,result.entitiesObserved());assertEquals(1,result.factsObserved());
            assertTrue(queries.stream().noneMatch(q->q.contains("ModelSuppliedType")||q.contains("MODEL_SUPPLIED_RELATION")||q.contains(GraphPolicyTest.TEXT)));
            assertTrue(parameters.stream().anyMatch(v->v.containsValue("MODEL_SUPPLIED_RELATION")));assertTrue(parameters.stream().anyMatch(v->v.containsValue(GraphPolicyTest.TEXT)));
        }
    }
    @Test void retrievalUsesReadSessionsReadTransactionsFixedQueriesAndScopedParameters(){
        List<String> queries=new ArrayList<>();List<Map<String,Object>> params=new ArrayList<>();
        TransactionContext tx=proxy(TransactionContext.class,(p,m,a)->{
            if(!m.getName().equals("run"))throw new UnsupportedOperationException(m.getName());String query=a[0].toString();queries.add(query);
            @SuppressWarnings("unchecked")Map<String,Object> values=(Map<String,Object>)a[1];params.add(values);
            if(query.startsWith("MATCH (r:KgRevision"))return result(List.of(Map.of("value",1)));
            if(query.startsWith("UNWIND $names")||query.startsWith("CALL db.index.fulltext"))return result(List.of(Map.of("key","node","type","Component","name","Aster","aliases",List.of(),"active",true)));
            if(query.startsWith("MATCH (e:KgEvidence")){
                Map<String,Object> row=new HashMap<>();row.put("id","evidence");row.put("subject","node");row.put("kind","ENTITY");row.put("chunkId","chunk");row.put("originalChunkId","original");row.put("documentId","doc");row.put("sourceName","source.txt");row.put("page",1);row.put("text","Aster");row.put("quote","Aster");row.put("start",0);row.put("end",5);row.put("properties","{}");row.put("provenance","{}");return result(List.of(row));
            }
            throw new AssertionError(query);
        });
        Session session=proxy(Session.class,(p,m,a)->{
            if(m.getName().equals("executeWrite"))throw new AssertionError("Graph retrieval attempted a write transaction.");
            if(m.getName().equals("executeRead"))return ((TransactionCallback<?>)a[0]).execute(tx);
            if(m.getName().equals("close"))return null;throw new UnsupportedOperationException(m.getName());
        });
        Driver driver=proxy(Driver.class,(p,m,a)->{if(m.getName().equals("session")){assertEquals(AccessMode.READ,((SessionConfig)a[0]).defaultAccessMode());return session;}if(m.getName().equals("close"))return null;throw new UnsupportedOperationException(m.getName());});
        var scope=new PluginExecutionContext("test","private","run","p",1,"r");
        try(var repository=new Neo4jChunkRepository(new Neo4jConfig("bolt://localhost:1","user","secret","neo4j","unused",1),driver)){
            var limits=new KnowledgeGraphStore.Limits(10,5,10,20,10,5,5,20000,Duration.ofSeconds(1),"plugintell_kg_entities",60);
            var plan=new KnowledgeGraphStore.Plan(List.of("Aster"),List.of(),"MATCH (n) DETACH DELETE n",Set.of(),Set.of(),"BOTH",0);
            var output=repository.retrieve(new SearchRequest("Aster",true),plan,scope,limits,List.of());assertEquals(1,output.chunks().size());
            assertTrue(queries.stream().noneMatch(q->q.contains("DETACH")||q.contains("CREATE ")||q.contains("MERGE ")||q.contains("DELETE ")));
            assertTrue(params.stream().filter(v->v.containsKey("scope")).allMatch(v->v.get("scope").equals(GraphPolicy.scopeKey(scope))));
            assertTrue(params.stream().anyMatch(v->v.containsKey("query")&&v.get("query").toString().contains("\"DELETE\"")));
        }
    }
}
