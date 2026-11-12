package com.core.docintel.plugins;
import com.core.docintel.pipeline.api.*;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.*;
import java.lang.reflect.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class Neo4JGraphWriterPluginTest {
    static ExtractedGraph graph(){
        var schema=Map.<String,Object>of("entities",List.of(Map.of("name","Person"),Map.of("name","Organization")),"relationships",List.of(Map.of("name","WORKS_FOR","head","Person","tail","Organization")));
        var a=new HashMap<String,Object>(Map.of("id","a","type","Person","name","Alice's `name`","aliases",List.of("Alice"),"properties",Map.of("id","cannot-overwrite","nested",Map.of("x",1)),"chunk_ids",Set.of("c")));
        a.put("confidence",null);
        var b=Map.<String,Object>of("id","b","type","Organization","name","Acme","chunk_ids",List.of("c"));
        var r=Map.<String,Object>of("id","r","type","WORKS_FOR","source_id","a","target_id","b","chunk_ids",List.of("c"));
        return new ExtractedGraph("doc",schema,List.of(a,b),List.of(r),List.of(new TextChunk("c","doc","source.pdf",2,0,"Alice works for Acme.")),Map.of("complete",true,"chunks_failed",0));
    }
    static ExtractedGraph with(ExtractedGraph g,List<Map<String,Object>> entities,List<Map<String,Object>> relationships,Map<String,Object> metadata){return new ExtractedGraph(g.documentId(),g.schema(),entities,relationships,g.chunks(),metadata);}
    @Test void rejectsIncompleteAndMalformedGraphsBeforeDatabaseAccess(){
        Driver forbidden=proxy(Driver.class,(p,m,a)->{throw new AssertionError("Database accessed before validation");});
        var plugin=new Neo4JGraphWriterPlugin(forbidden,PluginMessages.noop());var g=graph();
        assertThrows(IllegalArgumentException.class,()->plugin.execute(with(g,g.entities(),g.relationships(),Map.of("complete",false))));
        assertThrows(IllegalArgumentException.class,()->plugin.execute(with(g,g.entities(),g.relationships(),Map.of("complete",true,"chunks_failed",1))));
        var bad=new HashMap<>(g.relationships().getFirst());bad.put("target_id","missing");var badRelationship=bad;
        assertThrows(IllegalArgumentException.class,()->plugin.execute(with(g,g.entities(),List.of(badRelationship),g.metadata())));
        bad=new HashMap<>(g.entities().getFirst());bad.put("chunk_ids",List.of("other-document"));var wrongChunk=bad;
        assertThrows(IllegalArgumentException.class,()->plugin.execute(with(g,List.of(wrongChunk,g.entities().get(1)),g.relationships(),g.metadata())));
        var injected=new HashMap<>(g.entities().getFirst());injected.put("type","Person` MATCH (n) DETACH DELETE n //");
        assertThrows(IllegalArgumentException.class,()->plugin.execute(with(g,List.of(injected),List.of(),g.metadata())));
        assertThrows(IllegalArgumentException.class,()->plugin.execute(with(g,List.of(g.entities().getFirst(),g.entities().getFirst()),List.of(),g.metadata())));
    }
    @Test void writesParameterizedScopedBatchesInOneTransactionAndReportsCommittedCounts(){
        var database=new Database();var messages=new ArrayList<PluginMessage>();
        var plugin=new Neo4JGraphWriterPlugin(database.driver,messages::add);
        plugin.configure(Map.of("NEO4J_PASSWORD","never-log-this-password","NEO4J_DATABASE","research","NEO4J_GRAPH_NAMESPACE","history","NEO4J_GRAPH_BATCH_SIZE",1));
        var result=(GraphWriteResult)plugin.execute(graph());
        assertEquals(2,result.entitiesStored());assertEquals(1,result.relationshipsStored());assertEquals(1,result.chunksStored());assertEquals("research",database.database);assertEquals(1,database.commits);
        assertEquals(4,database.writes.stream().filter(w->w.params.containsKey("rows")).count());
        for(var write:database.writes){assertEquals("history",write.params.get("namespace"));assertEquals("doc",write.params.get("documentId"));assertFalse(write.query.contains("Alice"));assertFalse(write.query.contains("never-log"));}
        assertTrue(database.writes.stream().anyMatch(w->w.query.contains("[r:`WORKS_FOR`")));
        assertTrue(database.writes.stream().anyMatch(w->w.query.contains("MENTIONED_IN")));
        var entity=database.writes.stream().filter(w->w.query.contains("e:`Person`")).findFirst().orElseThrow();
        var row=(Map<?,?>)((List<?>)entity.params.get("rows")).getFirst();assertEquals("a",row.get("id"));assertTrue(row.get("propertiesJson").toString().contains("cannot-overwrite"));
        assertTrue(messages.stream().anyMatch(m->m.code().equals("graph.write.completed")));assertFalse(messages.toString().contains("never-log"));
        var before=database.writes.size();plugin.execute(graph());assertEquals(2,database.commits);
        assertEquals(database.writes.subList(0,before),database.writes.subList(before,database.writes.size()),"Repeated runs use identical MERGE queries and identity parameters.");
    }
    @Test void failedWriteDoesNotReportSuccessAndOmitsProviderDetails(){
        var database=new Database();database.fail=true;var messages=new ArrayList<PluginMessage>();var plugin=new Neo4JGraphWriterPlugin(database.driver,messages::add);plugin.configure(Map.of("NEO4J_PASSWORD","secret"));
        var failure=assertThrows(IllegalStateException.class,()->plugin.execute(graph()));assertEquals(0,database.commits);assertFalse(failure.toString().contains("private database body"));assertNull(failure.getCause());assertFalse(messages.stream().anyMatch(m->m.code().equals("graph.write.completed")));
    }
    @Test void missingConstraintsAreRejectedWhenProvisioningIsDisabled(){
        var database=new Database();database.constraints=false;var plugin=new Neo4JGraphWriterPlugin(database.driver,PluginMessages.noop());plugin.configure(Map.of("NEO4J_PASSWORD","secret","NEO4J_GRAPH_PROVISION_SCHEMA",false));
        assertTrue(assertThrows(IllegalStateException.class,()->plugin.execute(graph())).getMessage().contains("constraints"));assertEquals(0,database.commits);
    }
    record Write(String query,Map<String,Object> params){}
    static final class Database {
        int commits;boolean fail,constraints=true;String database;List<Write> writes=new ArrayList<>();
        final TransactionContext tx=proxy(TransactionContext.class,(p,m,a)->{
            if(m.getName().equals("run")){writes.add(new Write((String)a[0],new HashMap<>((Map<String,Object>)a[1])));if(fail&&writes.size()>1)throw new IllegalStateException("private database body including password");return result(List.of());}throw new AssertionError(m.getName());
        });
        final Session session=proxy(Session.class,(p,m,a)->{
            if(m.getName().equals("run"))return result(((String)a[0]).startsWith("SHOW")&&constraints?constraintRows():List.of());
            if(m.getName().equals("executeWrite")){var output=((TransactionCallback<?>)a[0]).execute(tx);commits++;return output;}
            if(m.getName().equals("close"))return null;throw new AssertionError(m.getName());
        });
        final Driver driver=proxy(Driver.class,(p,m,a)->{if(m.getName().equals("verifyConnectivity"))return null;if(m.getName().equals("session")){database=((SessionConfig)a[0]).database().orElseThrow();return session;}if(m.getName().equals("close"))return null;throw new AssertionError(m.getName());});
        static Result result(List<org.neo4j.driver.Record> rows){return proxy(Result.class,(p,m,a)->{if(m.getName().equals("list"))return rows;if(m.getName().equals("consume"))return null;throw new AssertionError(m.getName());});}
        static List<org.neo4j.driver.Record> constraintRows(){return List.of("ExtractedGraphDocument","ExtractedGraphEntity","ExtractedGraphChunk").stream().map(label->{Map<String,Object> fields=Map.of("entityType","NODE","type","UNIQUENESS","labelsOrTypes",List.of(label),"properties",label.equals("ExtractedGraphDocument")?List.of("namespace","documentId"):List.of("namespace","documentId","id"));return proxy(org.neo4j.driver.Record.class,(p,m,a)->Values.value(fields.get(a[0])));}).toList();}
    }
    @SuppressWarnings("unchecked") static <T>T proxy(Class<T> type,InvocationHandler handler){return (T)Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},handler);}
}
