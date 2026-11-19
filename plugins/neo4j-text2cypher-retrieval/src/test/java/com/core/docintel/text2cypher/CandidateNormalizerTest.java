package com.core.docintel.text2cypher;

import com.core.docintel.neo4j.GraphPolicy;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CandidateNormalizerTest {
    private static final String QUESTION="Which stations are between Lahore and Multan, in order, and why might this be a good choice for my family?";
    private static ObjectNode candidate(){
        var root=(ObjectNode)SemanticRetrievalRegressionTest.route(true,false,false).deepCopy();
        root.put("cypher","MATCH p=(a:Station {name:$start})-[:NEXT_STOP*1..64]->(b:Station {name:$end}) RETURN nodes(p) AS path");
        root.set("parameters",GraphPolicy.JSON.valueToTree(List.of(Map.of("name","journeyStart","value","Rawalpindi"),Map.of("name","journeyEnd","value","Karachi"),Map.of("name","start","value","Lahore"),Map.of("name","end","value","Multan"))));
        ((ObjectNode)root.path("intent")).set("anchors",root.arrayNode());return root;
    }
    @Test void fixesObservedRepresentationsWithoutAddingPredicatesOrMutatingTheOriginal(){
        var original=candidate();var result=CandidateNormalizer.normalize(original,new SchemaView(new SemanticRetrievalRegressionTest.RailwayGraph().schema));
        var draft=SemanticPlan.parse(result.candidate(),SemanticPlan.hints(SemanticRetrievalRegressionTest.QUESTION));
        assertEquals(Map.of("start","Lahore","end","Multan"),draft.query().parameters());
        assertEquals(original.path("cypher").asText().replace("nodes(p)","p"),draft.query().cypher());
        assertEquals(2,draft.intent().anchors().size());assertEquals(3,result.changes().size());
        assertEquals("Rawalpindi",draft.intent().journeyStart());assertEquals(4,original.path("parameters").size());assertTrue(original.path("intent").path("anchors").isEmpty());
        assertTrue(SemanticEvaluation.validate(draft,SemanticPlan.hints(QUESTION),64).isEmpty());
        var scopedHints=SemanticPlan.hints(SemanticRetrievalRegressionTest.QUESTION);
        assertEquals("Tezgam",scopedHints.subject());
        assertTrue(SemanticEvaluation.validate(draft,scopedHints,64).stream().anyMatch(i->i.code().equals("SUBJECT_SCOPE_REQUIRED")));
    }
    @Test void keepsMissingDuplicateAndMalformedParametersRejectedAndNeverAllowsWrites(){
        var view=new SchemaView(new SemanticRetrievalRegressionTest.RailwayGraph().schema);
        for(String query:List.of("MATCH (n) DELETE n RETURN $start, $end","RETURN $missing","RETURN $start, $end")){
            var root=candidate();root.put("cypher",query);
            if(query.equals("RETURN $start, $end"))((com.fasterxml.jackson.databind.node.ArrayNode)root.get("parameters")).add(root.get("parameters").get(2));
            var result=CandidateNormalizer.normalize(root,view);
            assertThrows(IllegalArgumentException.class,()->SemanticPlan.parse(result.candidate(),SemanticPlan.hints("List data")));
        }
        var root=candidate();((ObjectNode)root.path("parameters").get(0)).set("value",root.objectNode().put("invalid","shape"));
        assertThrows(IllegalArgumentException.class,()->CandidateNormalizer.normalize(root,view));
    }
    @Test void doesNotGuessAmbiguousUnknownAnchorsOrRewriteOtherExpressions(){
        var view=new SchemaView(new SemanticRetrievalRegressionTest.RailwayGraph().schema);
        var root=candidate();root.put("cypher","MATCH p=(a:Unknown {name:$start})-[:NEXT_STOP*1..64]->(b:Station {name:$end}) RETURN collect(nodes(p)) AS path");
        var result=CandidateNormalizer.normalize(root,view);
        assertTrue(result.candidate().path("cypher").asText().contains("collect(nodes(p))"));assertTrue(result.candidate().path("intent").path("anchors").isEmpty());
    }
    @Test void normalizedCandidateResolvesCanonicalEndpointsWithoutAnotherModelCall(){
        var fixture=new SemanticRetrievalRegressionTest.RailwayGraph();int[] calls={0};
        GraphAccess graph=new GraphAccess(){
            public void verify(){}public void close(){}public Map<String,Object> schema(int limit){return fixture.schema;}
            public Rows query(CypherGuard.Plan plan,int rows,int bytes){
                if(plan.cypher().contains("elementId(n)"))return fixture.query(plan,rows,bytes);
                assertEquals(Map.of("start","Lahore Jn.","end","Multan Cantt"),plan.parameters());assertTrue(plan.cypher().endsWith("RETURN p AS path"));
                List<Map<String,Object>> nodes=new ArrayList<>(),edges=new ArrayList<>();
                for(int i=8;i<=16;i++){nodes.add(SemanticRetrievalRegressionTest.RailwayGraph.node(i));if(i<16)edges.add(Map.of("id","r:"+i,"source","n:s"+i,"target","n:s"+(i+1)));}
                return new Rows(List.of(Map.of("path","native fixture")),nodes,edges,false,List.of(new NativePath(0,"path",nodes,edges)));
            }
        };
        var events=new ArrayList<com.core.docintel.pipeline.api.PluginMessage>();
        try(var plugin=SemanticRetrievalRegressionTest.plugin(graph,(s,d)->{calls[0]++;return candidate();},events,2)){
            var result=(com.core.docintel.pipeline.api.RetrievalResult)plugin.execute(new com.core.docintel.pipeline.api.SearchRequest(QUESTION,false));
            assertEquals(1,calls[0]);assertEquals(SemanticRetrievalRegressionTest.INTERMEDIATE,SemanticRetrievalRegressionTest.entities(result));assertEquals("PARTIAL",result.graph().get("semanticStatus"));
            assertTrue(events.stream().anyMatch(e->e.message().contains("Candidate normalization")));
        }
    }
    @Test void observedCandidateGetsActionableSubjectFeedbackThenReturnsAScopedNativePath(){
        var fixture=new SemanticRetrievalRegressionTest.RailwayGraph();int[] calls={0};
        try(var plugin=SemanticRetrievalRegressionTest.plugin(fixture,(s,d)->{
            if(++calls[0]==1)return candidate();
            assertTrue(GraphPolicy.parse(d).path("feedback").path("issues").toString().contains("SUBJECT_SCOPE_REQUIRED"));
            return SemanticRetrievalRegressionTest.route(true,false,false);
        },new ArrayList<>(),2)){
            var result=(com.core.docintel.pipeline.api.RetrievalResult)plugin.execute(new com.core.docintel.pipeline.api.SearchRequest(SemanticRetrievalRegressionTest.QUESTION,false));
            assertEquals(2,calls[0]);assertEquals(1,fixture.reads);assertEquals(SemanticRetrievalRegressionTest.INTERMEDIATE,SemanticRetrievalRegressionTest.entities(result));
        }
    }
}
