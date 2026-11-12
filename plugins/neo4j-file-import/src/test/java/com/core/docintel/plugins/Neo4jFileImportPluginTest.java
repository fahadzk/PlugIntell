package com.core.docintel.plugins;
import com.core.docintel.pipeline.api.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.neo4j.driver.*;
import org.neo4j.driver.summary.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class Neo4jFileImportPluginTest {
    @TempDir Path temporary;
    static final String CSV="""
        kind,id,label,name,source,target,type,properties
        relationship,r1,,,a,b,WORKS_FOR,
        node,a,Person,"Alice, A.",,,,"{""age"":30}"
        node,b,Organization,Acme,,,,
        """;
    @Test void parsesCsvQuotedValuesMultilineAndPropertiesBeforeWriting(){
        var plan=GraphImportFiles.csv(CSV);assertEquals(2,plan.nodes().size());assertEquals("Alice, A.",((Map<?,?>)plan.nodes().getFirst().get("properties")).get("name"));assertEquals(30,((Map<?,?>)plan.nodes().getFirst().get("properties")).get("age"));assertEquals(1,plan.relationships().size());
        var records=GraphImportFiles.records("\uFEFFid,name\r\nx,\"first\nsecond \"\"quoted\"\"\"\r\n");assertEquals("first\nsecond \"quoted\"",records.get(1).get(1));
        assertThrows(IllegalArgumentException.class,()->GraphImportFiles.csv("kind,id,label\nnode,a,Person\nnode,a,Person"));
        assertThrows(IllegalArgumentException.class,()->GraphImportFiles.csv("kind,id,label\nnode,a,Person`evil"));
        assertThrows(IllegalArgumentException.class,()->GraphImportFiles.csv("kind,id,label,properties\nnode,a,Person,\"{\"\"namespace\"\":\"\"other\"\"}\""));
        assertThrows(IllegalArgumentException.class,()->GraphImportFiles.records("id,name\nx,\"unterminated"));
        assertEquals(100001,GraphImportFiles.records("id\n"+"x\n".repeat(100000)).size());
    }
    @Test void splitsCypherOnlyOutsideStringsIdentifiersAndComments(){
        var statements=GraphImportFiles.statements("\uFEFF// heading\nCREATE (n {name:'a;b',value:\"// text\"}); /* skip ; */ MATCH (n:`semi;colon`) RETURN n; // end");
        assertEquals(2,statements.size());assertTrue(statements.getFirst().contains("'a;b'"));assertTrue(statements.get(1).contains("`semi;colon`"));
        assertEquals(List.of("RETURN 1"),GraphImportFiles.statements("/* outer /* inner ; */ outer ; */ RETURN 1;"));
        assertThrows(IllegalArgumentException.class,()->GraphImportFiles.statements(":begin\nCREATE (n);"));
        assertThrows(IllegalArgumentException.class,()->GraphImportFiles.statements("CREATE (n {name:'oops});"));
        assertThrows(IllegalArgumentException.class,()->GraphImportFiles.statements("/* no close"));
    }
    @Test void csvImportWritesNodesFirstThenRelationsAndCommitsOnce()throws Exception{
        var database=new Database();var messages=new ArrayList<PluginMessage>();var plugin=plugin(database,messages);
        Path file=temporary.resolve("graph.csv");Files.writeString(file,CSV);
        var result=(GraphImportResult)plugin.execute(file.toFile());
        assertEquals("CSV",result.format());assertEquals(1,database.commits);assertEquals("research",database.database);assertEquals(3,result.statementsExecuted());
        assertTrue(database.queries.getLast().contains("[r:`WORKS_FOR`"));assertTrue(database.queries.getFirst().contains("MERGE (n:ImportedGraphNode"));assertFalse(database.queries.toString().contains("Alice"));
        assertTrue(database.parameters.stream().allMatch(p->p.get("namespace").equals("history")));assertTrue(messages.stream().anyMatch(m->m.code().equals("graph.import.completed")));
    }
    @Test void cypherImportExecutesStatementsWithParametersAndRollsBackOnFailure()throws Exception{
        var database=new Database();var messages=new ArrayList<PluginMessage>();var plugin=plugin(database,messages);
        Path file=temporary.resolve("graph.cypher");Files.writeString(file,"CREATE (:Person {name:'Alice'}); CREATE (:Person {name:'Bob'});");
        database.failAt=2;var error=assertThrows(IllegalStateException.class,()->plugin.execute(file.toFile()));assertFalse(error.toString().contains("private password"));assertEquals(0,database.commits);assertTrue(database.closed);assertFalse(messages.stream().anyMatch(m->m.code().equals("graph.import.completed")));
        database=new Database();var second=plugin(database,new ArrayList<>());var result=(GraphImportResult)second.execute(file.toFile());assertEquals("CYPHER",result.format());assertEquals(2,result.statementsExecuted());assertEquals(1,database.commits);
    }
    @Test void malformedInputNeverTouchesDatabaseAndUnknownCsvEndpointsAbort()throws Exception{
        Driver forbidden=proxy(Driver.class,(p,m,a)->{throw new AssertionError("Database accessed for malformed input");});var plugin=new Neo4jFileImportPlugin(forbidden,PluginMessages.noop());plugin.configure(Map.of("NEO4J_PASSWORD","secret"));
        Path bad=temporary.resolve("bad.csv");Files.writeString(bad,"kind,id\ninvalid,x");assertThrows(IllegalArgumentException.class,()->plugin.execute(bad.toFile()));
        var database=new Database();database.unresolved=true;var writer=plugin(database,new ArrayList<>());Path file=temporary.resolve("unresolved.csv");Files.writeString(file,"kind,id,source,target,type\nrelationship,r,a,b,WORKS_FOR\n");
        assertTrue(assertThrows(IllegalArgumentException.class,()->writer.execute(file.toFile())).getMessage().contains("endpoint"));assertEquals(0,database.commits);
    }
    private static Neo4jFileImportPlugin plugin(Database db,List<PluginMessage> messages){var plugin=new Neo4jFileImportPlugin(db.driver,messages::add);plugin.configure(Map.of("NEO4J_PASSWORD","secret","NEO4J_DATABASE","research","NEO4J_IMPORT_NAMESPACE","history"));return plugin;}
    static final class Database {
        int commits,failAt;boolean closed,unresolved;String database;List<String> queries=new ArrayList<>();List<Map<String,Object>> parameters=new ArrayList<>();
        final Transaction tx=proxy(Transaction.class,(p,m,a)->{
            if(m.getName().equals("run")){queries.add((String)a[0]);parameters.add((Map<String,Object>)a[1]);if(queries.size()==failAt)throw new IllegalStateException("private password and statement body");long size=((Map<?,?>)a[1]).get("rows") instanceof List<?> rows?rows.size():1;return result(List.of(record(Map.of("written",unresolved?0:size))));}
            if(m.getName().equals("commit")){commits++;return null;}if(m.getName().equals("close")){closed=true;return null;}throw new AssertionError(m.getName());
        });
        final Session session=proxy(Session.class,(p,m,a)->{if(m.getName().equals("beginTransaction"))return tx;if(m.getName().equals("run"))return result(List.of(record(Map.of("entityType","NODE","type","UNIQUENESS","labelsOrTypes",List.of("ImportedGraphNode"),"properties",List.of("namespace","id")))));if(m.getName().equals("close"))return null;throw new AssertionError(m.getName());});
        final Driver driver=proxy(Driver.class,(p,m,a)->{if(m.getName().equals("session")){database=((SessionConfig)a[0]).database().orElseThrow();return session;}throw new AssertionError(m.getName());});
        static Result result(List<org.neo4j.driver.Record> rows){return proxy(Result.class,(p,m,a)->{if(m.getName().equals("list"))return rows;if(m.getName().equals("single"))return rows.getFirst();if(m.getName().equals("consume"))return proxy(ResultSummary.class,(x,method,y)->{if(method.getName().equals("counters"))return proxy(SummaryCounters.class,(z,counter,args)->counter.getReturnType()==int.class?1:false);throw new AssertionError(method.getName());});throw new AssertionError(m.getName());});}
        static org.neo4j.driver.Record record(Map<String,Object> fields){return proxy(org.neo4j.driver.Record.class,(p,m,a)->Values.value(fields.get(a[0])));}
    }
    @SuppressWarnings("unchecked") static <T>T proxy(Class<T> type,InvocationHandler handler){return (T)Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},handler);}
}
