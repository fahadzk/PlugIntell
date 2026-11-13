package com.core.docintel.text2cypher;
import com.core.docintel.neo4j.GraphPolicy;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.*;
import org.neo4j.driver.internal.*;
import org.neo4j.driver.summary.*;
import java.lang.reflect.*;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class Neo4jReadGraphTest {
    @Test void connectionVerificationAlsoChecksTheConfiguredDatabaseInReadMode(){
        var fixture=new Fixture(List.of(),QueryType.READ_ONLY);try(var graph=fixture.graph()){assertDoesNotThrow(graph::verify);assertEquals(List.of("RETURN 1 AS connected"),fixture.queries);assertTrue(fixture.rolledBack);}
    }
    @Test void checksExplainUsesReadSessionPreservesOrderAndRollsBackAfterRowCap(){
        var fixture=new Fixture(List.of(Map.of("station","Raiwind"),Map.of("station","Pattoki"),Map.of("station","Okara")),QueryType.READ_ONLY);
        try(var graph=fixture.graph()){
            var rows=graph.query(CypherGuardTest.plan("MATCH (n) RETURN n.name AS station ORDER BY n.sequence"),2,262144);
            assertEquals(List.of("Raiwind","Pattoki"),rows.rows().stream().map(row->row.get("station")).toList());assertTrue(rows.truncated());
            assertEquals(List.of("EXPLAIN MATCH (n) RETURN n.name AS station ORDER BY n.sequence","MATCH (n) RETURN n.name AS station ORDER BY n.sequence"),fixture.queries);assertTrue(fixture.rolledBack);
        }
    }
    @Test void rejectsNonReadClassificationBeforeExecutingQuery(){
        var fixture=new Fixture(List.of(),QueryType.READ_WRITE);
        try(var graph=fixture.graph()){
            assertThrows(IllegalArgumentException.class,()->graph.query(CypherGuardTest.plan("RETURN 1"),10,262144));assertEquals(List.of("EXPLAIN RETURN 1"),fixture.queries);assertTrue(fixture.rolledBack);
        }
    }
    @Test void serializesNodesAndDirectionalRelationshipsWithoutLosingLabels(){
        var a=new InternalNode(1,"a",List.of("Station"),Map.of("name",Values.value("Lahore")));
        var b=new InternalNode(2,"b",List.of("Station"),Map.of("name",Values.value("Raiwind")));
        var edge=new InternalRelationship(3,"edge",1,"a",2,"b","NEXT_STOP",Map.of("train",Values.value("Tezgam")));
        var fixture=new Fixture(List.of(Map.of("a",a,"b",b),Map.of("edge",edge)),QueryType.READ_ONLY);
        try(var graph=fixture.graph()){
            var result=graph.query(CypherGuardTest.plan("MATCH (a)-[r]->(b) RETURN a,r,b"),10,262144);
            assertEquals(2,result.nodes().size());assertEquals(1,result.edges().size());assertEquals("n:a",result.edges().getFirst().get("source"));assertEquals("n:b",result.edges().getFirst().get("target"));
            assertEquals("Lahore",result.nodes().stream().filter(n->n.get("id").equals("n:a")).findFirst().orElseThrow().get("label"));assertFalse(result.truncated());assertDoesNotThrow(()->GraphPolicy.encode(result.rows()));
        }
    }
    @Test void rejectsHugeValuesAndCapsAcceptedResultBytes(){
        var fixture=new Fixture(List.of(Map.of("text","x".repeat(16001))),QueryType.READ_ONLY);
        try(var graph=fixture.graph()){assertThrows(IllegalStateException.class,()->graph.query(CypherGuardTest.plan("RETURN 1"),10,262144));assertTrue(fixture.rolledBack);}
        fixture=new Fixture(List.of(Map.of("text","x".repeat(600)),Map.of("text","y".repeat(600))),QueryType.READ_ONLY);
        try(var graph=fixture.graph()){var result=graph.query(CypherGuardTest.plan("RETURN 1"),10,6000);assertEquals(1,result.rows().size());assertTrue(result.truncated());}
    }
    static final class Fixture {
        final List<String> queries=new ArrayList<>();final List<Map<String,Object>> rows;final QueryType queryType;boolean rolledBack;
        Fixture(List<Map<String,Object>> rows,QueryType queryType){this.rows=rows;this.queryType=queryType;}
        Neo4jReadGraph graph(){
            Transaction tx=proxy(Transaction.class,(p,m,a)->{
                if(m.getName().equals("run")){String query=a[0].toString();queries.add(query);return result(query.startsWith("EXPLAIN")?List.of():rows,queryType);}
                if(m.getName().equals("rollback")){rolledBack=true;return null;}if(m.getName().equals("close"))return null;throw new AssertionError(m.getName());
            });
            Session session=proxy(Session.class,(p,m,a)->{if(m.getName().equals("beginTransaction")){assertTrue(a[0] instanceof TransactionConfig);return tx;}if(m.getName().equals("close"))return null;throw new AssertionError("Unexpected session operation: "+m.getName());});
            Driver driver=proxy(Driver.class,(p,m,a)->{if(m.getName().equals("session")){var settings=(SessionConfig)a[0];assertEquals(AccessMode.READ,settings.defaultAccessMode());assertEquals("pakistan-railways",settings.database().orElseThrow());return session;}if(m.getName().equals("close")||m.getName().equals("verifyConnectivity"))return null;throw new AssertionError(m.getName());});
            return new Neo4jReadGraph(driver,"pakistan-railways",Duration.ofSeconds(2));
        }
    }
    private static Result result(List<Map<String,Object>> rows,QueryType type){
        var iterator=rows.iterator();ResultSummary summary=proxy(ResultSummary.class,(p,m,a)->{if(m.getName().equals("queryType"))return type;throw new AssertionError(m.getName());});
        return proxy(Result.class,(p,m,a)->switch(m.getName()){
            case "hasNext"->iterator.hasNext();case "next"->{var row=iterator.next();yield new InternalRecord(new ArrayList<>(row.keySet()),row.values().stream().map(Values::value).toArray(Value[]::new));}case "consume"->summary;default->throw new AssertionError(m.getName());});
    }
    @SuppressWarnings("unchecked") static <T>T proxy(Class<T> type,InvocationHandler handler){return (T)Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},handler);}
}
