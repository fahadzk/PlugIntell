package com.core.docintel.text2cypher;

import com.core.docintel.neo4j.GraphPolicy;
import com.core.docintel.pipeline.api.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PathBindingIdentityRegressionTest {
    static ObjectNode candidate(){
        var root=(ObjectNode)SemanticRetrievalRegressionTest.route(true,false,false).deepCopy();
        root.put("cypher","MATCH (subject:Train {id:$train})-[:STOPS_AT]->(start:Station {name:$start})-[:NEXT_STOP*1..64]->(end:Station {name:$end}) RETURN p AS path");
        var anchors=(com.fasterxml.jackson.databind.node.ArrayNode)root.path("intent").path("anchors");((ObjectNode)anchors.get(2)).put("property","id");
        var description=(ObjectNode)root.path("intent").path("parts").get(1);description.put("supported",true);description.set("schemaEvidence",GraphPolicy.JSON.valueToTree(List.of("Train.origin","Train.destination")));description.set("resultColumns",GraphPolicy.JSON.valueToTree(List.of("origin","destination")));return root;
    }
    static class Graph implements GraphAccess {
        final SemanticRetrievalRegressionTest.RailwayGraph base=new SemanticRetrievalRegressionTest.RailwayGraph();
        boolean ambiguous,partial;int executions;CypherGuard.Plan last;
        Graph(){base.schema.put("nodes",List.of(Map.of("labels",List.of("Train"),"properties",Map.of("id","INTEGER","name","STRING","origin","STRING","destination","STRING")),Map.of("labels",List.of("Station"),"properties",Map.of("name","STRING"))));}
        public void verify(){}public void close(){}public Map<String,Object> schema(int limit){return base.schema;}
        public Rows query(CypherGuard.Plan plan,int rows,int bytes){
            if(plan.cypher().contains("elementId(n) AS elementId")){
                if(plan.cypher().contains("`Train`")&&plan.cypher().contains("n.`id`"))return new Rows(List.of(),List.of(),List.of(),false);
                if(plan.cypher().contains("`Train`")){
                    if(partial)return new Rows(List.of(Map.of("elementId","t1","value","Tezgam Express")),List.of(),List.of(),false);
                    if(ambiguous)return new Rows(List.of(Map.of("elementId","t1","value","Tezgam"),Map.of("elementId","t2","value","Tezgam")),List.of(),List.of(),false);
                }
                return base.query(plan,rows,bytes);
            }
            executions++;last=plan;
            assertTrue(plan.cypher().contains("MATCH p=(start:Station"));assertTrue(plan.cypher().contains("subject:Train {name:$train}"));assertFalse(plan.cypher().contains("{id:"));
            assertEquals(Map.of("train","Tezgam","start","Lahore Jn.","end","Multan Cantt"),plan.parameters());
            return base.query(plan,rows,bytes);
        }
    }
    @Test void repairsOnlyTheUniqueEndpointPathBindingAndPreservesMembershipOutsideIt(){
        var root=candidate();var result=CandidateNormalizer.normalize(root,new SchemaView(new Graph().schema(256)));
        assertEquals("MATCH (subject:Train {id:$train})-[:STOPS_AT]->(start:Station {name:$start}) MATCH p=(start:Station {name:$start})-[:NEXT_STOP*1..64]->(end:Station {name:$end}) RETURN p AS path",result.candidate().path("cypher").asText());
        assertTrue(result.changes().stream().anyMatch(c->c.contains("Bound the omitted native path variable")));
        assertFalse(root.path("cypher").asText().contains("MATCH p="));
        root.put("cypher","MATCH (a:Station {name:$start})-[:NEXT_STOP*1..64]->(b:Station {name:$end}) RETURN p AS path");
        assertTrue(CandidateNormalizer.normalize(root,new SchemaView(new Graph().schema(256))).candidate().path("cypher").asText().startsWith("MATCH p="));
    }
    @Test void observedCandidateProducesSevenOrderedStationsUsingExactSubjectNameEvidence(){
        var graph=new Graph();var events=new ArrayList<PluginMessage>();int[] calls={0};
        try(var plugin=SemanticRetrievalRegressionTest.plugin(graph,(s,d)->{calls[0]++;return candidate();},events,1)){
            var result=(RetrievalResult)plugin.execute(new SearchRequest(SemanticRetrievalRegressionTest.QUESTION,false));
            assertEquals(1,calls[0]);assertEquals(1,graph.executions);assertEquals(SemanticRetrievalRegressionTest.INTERMEDIATE,SemanticRetrievalRegressionTest.entities(result));assertEquals("PARTIAL",result.graph().get("semanticStatus"));
            assertTrue(events.stream().anyMatch(e->e.message().contains("Anchor normalization")&&e.message().contains("Train.id to Train.name")));
            assertTrue(((List<?>)result.graph().get("resolvedAnchors")).toString().contains("EXACT_UNIQUE_DISPLAY_NAME"));
        }
    }
    @Test void ambiguousOrPartialSubjectNameDoesNotPermitIdentityRewritingOrExecution(){
        for(boolean ambiguous:List.of(true,false)){
            var graph=new Graph();graph.ambiguous=ambiguous;graph.partial=!ambiguous;var events=new ArrayList<PluginMessage>();
            try(var plugin=SemanticRetrievalRegressionTest.plugin(graph,(s,d)->candidate(),events,0)){
                assertThrows(IllegalArgumentException.class,()->plugin.execute(new SearchRequest(SemanticRetrievalRegressionTest.QUESTION,false)));assertEquals(0,graph.executions);
                assertTrue(events.stream().anyMatch(e->e.message().contains("UNRESOLVED_SUBJECT_ANCHOR")));
            }
        }
    }
    @Test void complexIdentityPredicateAndUnexpectedEndpointBindingAreNotRewritten(){
        var root=candidate();root.put("cypher","MATCH (subject:Train)-[:STOPS_AT]->(start:Station {name:$start})-[:NEXT_STOP*1..64]->(end:Station {name:$end}) WHERE subject.id=$train RETURN p AS path");
        var normal=CandidateNormalizer.normalize(root,new SchemaView(new Graph().schema(256)));
        assertFalse(normal.candidate().path("cypher").asText().contains("MATCH p="));
        var graph=new Graph();var view=new SchemaView(graph.schema(256));var draft=SemanticPlan.parse(root,SemanticPlan.hints(SemanticRetrievalRegressionTest.QUESTION));
        var repair=SubjectIdentityRepair.repair(graph,view,draft,SemanticPlan.hints(SemanticRetrievalRegressionTest.QUESTION),view.resolve(graph,draft.intent()));
        assertTrue(repair.changes().isEmpty());assertTrue(repair.draft().query().cypher().contains("subject.id=$train"));
    }
}
