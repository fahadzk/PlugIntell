package com.core.docintel.neo4j;
import com.core.docintel.pipeline.api.*;
import com.core.docintel.pipeline.api.KnowledgeGraph.*;
import com.core.docintel.neo4j.KnowledgeGraphStore.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.neo4j.driver.*;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Opt in against a disposable Neo4j 5 database; no external service is needed by routine tests. */
@EnabledIfEnvironmentVariable(named="KG_TEST_NEO4J_URI",matches=".+")
class KnowledgeGraphNeo4jIntegrationTest {
    private static final Duration TIMEOUT=Duration.ofSeconds(20);
    private final PluginExecutionContext scope=new PluginExecutionContext("kg-test-"+UUID.randomUUID(),"private","test-run","test-pipeline",7,"extract");
    private Neo4jConfig config(){String password=System.getenv("KG_TEST_NEO4J_PASSWORD");Assumptions.assumeTrue(password!=null&&!password.isBlank(),"Set KG_TEST_NEO4J_PASSWORD.");return new Neo4jConfig(System.getenv("KG_TEST_NEO4J_URI"),System.getenv().getOrDefault("KG_TEST_NEO4J_USERNAME","neo4j"),password,System.getenv().getOrDefault("KG_TEST_NEO4J_DATABASE","neo4j"),"unused",1);}
    private void prepare(Neo4jChunkRepository repo)throws Exception{
        try{repo.graphPreflight(true,"plugintell_kg_entities",TIMEOUT);}catch(IllegalStateException e){if(!e.getMessage().contains("ONLINE"))throw e;}
        for(int n=0;;n++){try{repo.graphPreflight(false,"plugintell_kg_entities",TIMEOUT);return;}catch(IllegalStateException e){if(n>=30)throw e;Thread.sleep(200);}}
    }
    private static Limits limits(){return new Limits(20,10,30,100,50,20,20,100000,TIMEOUT,"plugintell_kg_entities",60);}
    private RetrievalResult retrieve(Neo4jChunkRepository repo,PluginExecutionContext c,int hops){return repo.retrieve(new SearchRequest("Aster",true),new Plan(List.of("Aster"),List.of(),"Aster",Set.of(),Set.of(),"OUT",hops),c,limits(),List.of());}
    private void clean(Neo4jConfig config){try(Driver driver=GraphDatabase.driver(config.uri(),AuthTokens.basic(config.username(),config.password()));Session session=driver.session(SessionConfig.forDatabase(config.database()))){session.run("MATCH (n) WHERE n.scopeKey=$scope OR (n:KgRevision AND n.key=$scope) DETACH DELETE n",Map.of("scope",GraphPolicy.scopeKey(scope))).consume();}}
    @Test void retriesSharedSourcesChangedChunksAndEmptyReplacement()throws Exception{
        var config=config();try(var repo=new Neo4jChunkRepository(config)){
            prepare(repo);var policy=new GraphPolicy(GraphPolicyTest.propertyConfig());
            var a=policy.validate(GraphPolicyTest.extraction(),GraphPolicyTest.chunk("one"),scope,Map.of("model","fixture","schemaHash","schema","promptHash","prompt"));
            var first=repo.replace(a,scope,TIMEOUT);assertEquals(2,first.entitiesCreated());assertEquals(1,first.factsCreated());assertEquals(3,first.evidenceCreated());
            var replay=repo.replace(a,scope,TIMEOUT);assertTrue(replay.unchanged());assertEquals(0,replay.entitiesCreated());assertEquals(first.graphRevision(),replay.graphRevision());
            var b=policy.validate(GraphPolicyTest.extraction(),GraphPolicyTest.chunk("two"),scope,a.provenance());var shared=repo.replace(b,scope,TIMEOUT);assertEquals(0,shared.factsCreated());assertEquals(0,shared.entitiesCreated());
            var both=retrieve(repo,scope,1);assertEquals(2,both.chunks().size());assertEquals(2,((List<?>)both.graph().get("evidence")).stream().map(v->(Map<?,?>)v).filter(e->e.get("kind").equals("FACT")).count());
            var changed=new TextChunk("new-content-id","doc","manual.txt",2,0,"No extracted facts remain.");repo.replace(new Extraction(changed,List.of(),List.of(),a.provenance()),scope,TIMEOUT);
            var remaining=retrieve(repo,scope,1);assertEquals(1,remaining.chunks().size());assertTrue(GraphPolicy.encode(remaining.graph()).contains("two"));assertFalse(GraphPolicy.encode(remaining.graph().get("evidence")).contains("\"originalChunkId\":\"one\""));
            var other=new PluginExecutionContext(scope.namespace(),"public",null,null,null,null);assertTrue(retrieve(repo,other,1).chunks().isEmpty());
            repo.replace(new Extraction(GraphPolicyTest.chunk("two"),List.of(),List.of(),a.provenance()),scope,TIMEOUT);assertTrue(retrieve(repo,scope,1).chunks().isEmpty());
        }finally{clean(config);}
    }
    @Test void unauthorizedIntermediateNodeCannotBeTraversedAndRetrievalDoesNotChangeRevision()throws Exception{
        var config=config();try(var repo=new Neo4jChunkRepository(config)){
            prepare(repo);var root=GraphPolicyTest.extraction();var entities=(com.fasterxml.jackson.databind.node.ArrayNode)root.path("entities");var third=entities.get(1).deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)third).put("id","c").put("name","Cirrus");((com.fasterxml.jackson.databind.node.ObjectNode)third.path("properties")).put("identifier","R-9");((com.fasterxml.jackson.databind.node.ObjectNode)third.path("evidence")).put("quote","Cirrus rack R-9");entities.add(third);
            var relations=(com.fasterxml.jackson.databind.node.ArrayNode)root.path("relationships");var next=relations.get(0).deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)next).put("source","b").put("target","c");((com.fasterxml.jackson.databind.node.ObjectNode)next.path("evidence")).put("quote","Boreal rack R-8 powers Cirrus rack R-9.");relations.add(next);
            var chunk=new TextChunk("chain","doc","manual.txt",1,0,GraphPolicyTest.TEXT+" Boreal rack R-8 powers Cirrus rack R-9.");var extraction=new GraphPolicy(GraphPolicyTest.propertyConfig()).validate(root,chunk,scope,Map.of());var write=repo.replace(extraction,scope,TIMEOUT);
            assertEquals(3,((List<?>)retrieve(repo,scope,2).graph().get("nodes")).size());
            String middle=extraction.entities().get(1).key();
            try(Driver driver=GraphDatabase.driver(config.uri(),AuthTokens.basic(config.username(),config.password()));Session s=driver.session(SessionConfig.forDatabase(config.database()))){s.run("MATCH (n:KgEntity {key:$key}) SET n.scopeKey=$other",Map.of("key",middle,"other",GraphPolicy.hash("unreadable"))).consume();}
            var result=retrieve(repo,scope,2);assertEquals(1,((List<?>)result.graph().get("nodes")).size());assertTrue(((List<?>)result.graph().get("relationships")).isEmpty());assertEquals(write.graphRevision(),((Map<?,?>)result.graph().get("diagnostics")).get("revisionAtEnd"));
            // Restore the fixture's scope so cleanup removes all its nodes.
            try(Driver driver=GraphDatabase.driver(config.uri(),AuthTokens.basic(config.username(),config.password()));Session s=driver.session(SessionConfig.forDatabase(config.database()))){s.run("MATCH (n:KgEntity {key:$key}) SET n.scopeKey=$scope",Map.of("key",middle,"scope",GraphPolicy.scopeKey(scope))).consume();}
        }finally{clean(config);}
    }
}
