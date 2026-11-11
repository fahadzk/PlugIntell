package com.core.docintel.neo4j;
import com.core.docintel.pipeline.api.*;
import com.core.docintel.neo4j.GraphTraversal.*;
import com.core.docintel.neo4j.KnowledgeGraphStore.*;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class GraphTraversalTest {
    static class Reader implements GraphTraversal.Reader {
        final Node a=new Node("a","Component","Atlas",List.of("A")),b=new Node("b","Component","Boreal",List.of()),c=new Node("c","Component","Cirrus",List.of());
        final Map<String,Node> nodes=new LinkedHashMap<>(Map.of("a",a,"b",b,"c",c));
        final List<Edge> edges=new ArrayList<>(List.of(new Edge("ab","a","POWERS","b",a,b),new Edge("bc","b","POWERS","c",b,c),new Edge("ca","c","POWERS","a",c,a)));
        int explored,calls;boolean changed;
        public long revision(){return changed?2:1;}
        public List<Node> exact(List<String> terms,int limit){return List.of(a);}
        public List<Node> lexical(String text,int limit){return List.of(a);}
        public Neighbors neighbors(String key,String direction,int limit,Set<String> types,Set<String> relations){
            calls++;var candidates=edges.stream().filter(e->direction.equals("BOTH")&&(e.source().equals(key)||e.target().equals(key))||direction.equals("OUT")&&e.source().equals(key)||direction.equals("IN")&&e.target().equals(key)).toList();
            var scanned=candidates.stream().limit(limit).toList();explored+=scanned.size();return new Neighbors(scanned,scanned.size(),scanned.size()>=limit);
        }
        public List<Support> evidence(String subject,int limit){return List.of(new Support(subject+"-e",subject,"FACT","chunk-"+subject,"doc","source.txt",1,"Atlas powers Boreal. Boreal powers Cirrus.","Atlas powers Boreal.",0,20,"{}",Map.of("pipelineVersion",7)));}
    }
    static Limits limits(int degree,int chars){return new Limits(20,10,50,100,50,20,degree,chars,Duration.ofSeconds(2),"plugintell_kg_entities",60);}
    static Plan plan(String direction,int hops){return new Plan(List.of("Atlas"),List.of(),"Atlas",Set.of(),Set.of(),direction,hops);}
    @Test void ranksStrongSeedMultiHopEvidenceAheadOfWeakSeedDirectEvidence(){
        var reader=new Reader(){@Override public List<Node> lexical(String text,int limit){return List.of(a,c);}};
        var result=new GraphTraversal(reader,limits(10,100000)).search(new SearchRequest("Atlas",true),plan("OUT",2),List.of());
        var relationships=(List<?>)result.graph().get("relationships");
        assertEquals(List.of("ab","bc","ca"),relationships.stream().map(e->((Map<?,?>)e).get("id")).toList());
        double previous=Double.POSITIVE_INFINITY;
        for(Object path:(List<?>)result.graph().get("paths")){
            double score=((Number)((Map<?,?>)path).get("score")).doubleValue();assertTrue(score<=previous);previous=score;
        }
    }
    @Test void expandsBoundedHopsWithoutInventingDirectEdges(){
        var reader=new Reader();var result=new GraphTraversal(reader,limits(10,100000)).search(new SearchRequest("Atlas",true),plan("OUT",2),List.of());
        assertEquals(3,((List<?>)result.graph().get("nodes")).size());assertEquals(2,((List<?>)result.graph().get("relationships")).size());
        assertFalse(((List<?>)result.graph().get("relationships")).stream().map(e->(Map<?,?>)e).anyMatch(e->e.get("source").equals("a")&&e.get("target").equals("c")));
        assertEquals(2,reader.calls);assertTrue(result.chunks().size()<=20);assertFalse(result.toString().contains("Atlas powers"));
        for(Object path:(List<?>)result.graph().get("paths")){var map=(Map<?,?>)path;var nodes=(List<?>)map.get("nodes");assertEquals(nodes.size(),new HashSet<>(nodes).size());assertEquals(false,map.get("inferredDirectRelationship"));}
    }
    @Test void directionAndDegreeCapsApplyBeforeTraversal(){
        var out=new Reader();var in=new Reader();var r1=new GraphTraversal(out,limits(1,100000)).search(new SearchRequest("Atlas",true),plan("OUT",3),List.of());var r2=new GraphTraversal(in,limits(1,100000)).search(new SearchRequest("Atlas",true),plan("IN",1),List.of());
        assertTrue(GraphPolicy.encode(r1.graph().get("relationships")).contains("ab"));assertTrue(GraphPolicy.encode(r2.graph().get("relationships")).contains("ca"));
        assertTrue(out.explored<=3);assertEquals(1,in.calls);
    }
    @Test void preservesAmbiguousSeedsAndSmallBudgetsReturnNoUnsupportedContext(){
        var reader=new Reader(){@Override public List<Node> lexical(String text,int limit){return List.of(a,new Node("other","Component","Atlas",List.of()));}};
        var result=new GraphTraversal(reader,limits(1,100000)).search(new SearchRequest("Atlas",true),plan("OUT",0),List.of());
        assertEquals(2,((List<?>)result.graph().get("nodes")).size());assertTrue(GraphPolicy.encode(result.graph().get("diagnostics")).contains("Ambiguous"));
        var small=new GraphTraversal(new Reader(),limits(1,256)).search(new SearchRequest("Atlas",true),plan("BOTH",3),List.of());
        assertTrue(small.chunks().isEmpty());assertEquals(true,((Map<?,?>)small.graph().get("diagnostics")).get("truncated"));
    }
    @Test void planValidationRejectsOperationsScopeAndHopEscalation(){
        var schema=new Schema(Set.of("Component"),Set.of("POWERS"),false);
        String valid="{\"entities\":[\"Atlas\"],\"aliases\":[],\"semanticText\":\"Atlas\",\"entityTypes\":[],\"relationshipTypes\":[],\"direction\":\"OUT\",\"hops\":2}";
        assertEquals(2,GraphPlans.validate(GraphPolicy.parse(valid),schema,Set.of(),Set.of(),3,"OUT").hops());
        for(String invalid:List.of(valid.replace("\"hops\":2","\"hops\":4"),valid.replace("\"OUT\"","\"BOTH\""),valid.replace("\"aliases\":[]","\"aliases\":[],\"namespace\":\"admin\""),valid.replace("\"entityTypes\":[]","\"entityTypes\":[\"Unknown\"]")))assertThrows(IllegalArgumentException.class,()->GraphPlans.validate(GraphPolicy.parse(invalid),schema,Set.of(),Set.of(),3,"OUT"));
    }
    @Test void interruptedTraversalStops(){Thread.currentThread().interrupt();try{assertThrows(java.util.concurrent.CancellationException.class,()->GraphTraversal.cancelled());}finally{Thread.interrupted();}}
}
