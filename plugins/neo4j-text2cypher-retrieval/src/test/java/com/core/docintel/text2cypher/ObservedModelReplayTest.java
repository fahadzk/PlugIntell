package com.core.docintel.text2cypher;

import com.core.docintel.neo4j.GraphPolicy;
import com.core.docintel.pipeline.api.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Replays the actual failure shapes without calling a billable model or a live database. */
class ObservedModelReplayTest {
    private static ObjectNode first(){
        var root=(ObjectNode)SemanticRetrievalRegressionTest.route(true,false,false).deepCopy();
        root.put("cypher","MATCH p=(a:Station {name:$start})-[:NEXT_STOP*1..64]->(b:Station {name:$end}) RETURN nodes(p) AS path");
        ((ObjectNode)root.path("intent")).set("anchors",root.arrayNode());return root;
    }
    private static ObjectNode second(){
        var root=(ObjectNode)SemanticRetrievalRegressionTest.route(true,false,false).deepCopy();
        root.put("cypher","MATCH p=(a:Train {id:$subject})-[:STOPS_AT]->(start:Station {name:$journeyStart})-[:NEXT_STOP*1..64]->(end:Station {name:$betweenEnd})-[:STOPS_AT]->(b:Train {id:$subject}) RETURN p AS path");
        root.set("parameters",GraphPolicy.JSON.valueToTree(List.of(Map.of("name","subject","value","Tezgam"),Map.of("name","journeyStart","value","Rawalpindi"),Map.of("name","betweenEnd","value","Multan"))));
        var intent=(ObjectNode)root.path("intent");intent.put("subrouteStart","Rawalpindi");
        intent.set("anchors",GraphPolicy.JSON.valueToTree(List.of(SemanticRetrievalRegressionTest.anchor("SUBROUTE_START","Rawalpindi","journeyStart"),SemanticRetrievalRegressionTest.anchor("SUBROUTE_END","Multan","betweenEnd"),Map.of("role","SUBJECT","mention","Tezgam","label","Train","property","id","parameter","subject"))));
        intent.set("parts",GraphPolicy.JSON.valueToTree(List.of(SemanticRetrievalRegressionTest.part("1","PATH",true,List.of("path"),List.of("Station.name","NEXT_STOP")))));return root;
    }
    @Test void numericOpaqueIdsAreAcceptedWhileMalformedIdsDuplicatesAndSupportFlagsRemainRejected(){
        var root=second();assertEquals("1",SemanticPlan.parse(root,SemanticPlan.hints(SemanticRetrievalRegressionTest.QUESTION)).intent().parts().getFirst().id());
        for(String id:List.of("","bad\nidentifier","x".repeat(65))){var invalid=second();((ObjectNode)invalid.path("intent").path("parts").get(0)).put("id",id);assertThrows(IllegalArgumentException.class,()->SemanticPlan.parse(invalid,SemanticPlan.hints("List information")));}
        var duplicate=second();((com.fasterxml.jackson.databind.node.ArrayNode)duplicate.path("intent").path("parts")).add(duplicate.path("intent").path("parts").get(0));assertThrows(IllegalArgumentException.class,()->SemanticPlan.parse(duplicate,SemanticPlan.hints("List information")));
        var invalid=second();((ObjectNode)invalid.path("intent").path("parts").get(0)).put("supported","true");assertThrows(IllegalArgumentException.class,()->SemanticPlan.parse(invalid,SemanticPlan.hints("List information")));
    }
    @Test void replayReportsSemanticErrorsThenAcceptsACorrectReadWithinTwoRepairs(){
        var graph=new SemanticRetrievalRegressionTest.RailwayGraph();int[] calls={0};var events=new ArrayList<PluginMessage>();
        try(var plugin=SemanticRetrievalRegressionTest.plugin(graph,(s,d)->{
            var data=GraphPolicy.parse(d);switch(++calls[0]){
                case 1:return first();
                case 2:assertTrue(data.path("feedback").path("issues").toString().contains("SUBJECT_SCOPE_REQUIRED"));return second();
                default:
                    assertTrue(data.path("feedback").path("issues").toString().contains("SUBROUTE_ENDPOINT_MISMATCH"));
                    assertFalse(data.path("feedback").path("issues").toString().contains("QUERY_CONTRACT_INVALID"));
                    assertEquals("Lahore",data.path("feedback").path("requiredInterpretation").path("subrouteStart").asText());
                    assertEquals("Multan",data.path("feedback").path("requiredInterpretation").path("subrouteEnd").asText());
                    assertEquals("Tezgam",data.path("feedback").path("requiredInterpretation").path("subject").asText());
                    assertFalse(data.path("feedback").path("requiredInterpretation").path("descriptiveRequest").asText().isBlank());
                    return SemanticRetrievalRegressionTest.route(true,false,false);
            }
        },events,2)){
            var result=(RetrievalResult)plugin.execute(new SearchRequest(SemanticRetrievalRegressionTest.QUESTION,false));
            assertEquals(3,calls[0]);assertEquals(1,graph.reads);assertEquals(SemanticRetrievalRegressionTest.INTERMEDIATE,SemanticRetrievalRegressionTest.entities(result));assertEquals("PARTIAL",result.graph().get("semanticStatus"));
        }
    }
    @Test void replayHonorsAConfiguredSingleRepairWithoutExecutingEitherWrongQuery(){
        var graph=new SemanticRetrievalRegressionTest.RailwayGraph();int[] calls={0};var events=new ArrayList<PluginMessage>();
        try(var plugin=SemanticRetrievalRegressionTest.plugin(graph,(s,d)->++calls[0]==1?first():second(),events,1)){
            assertThrows(IllegalArgumentException.class,()->plugin.execute(new SearchRequest(SemanticRetrievalRegressionTest.QUESTION,false)));assertEquals(2,calls[0]);assertEquals(0,graph.reads);
            assertTrue(events.stream().anyMatch(e->e.message().contains("SUBROUTE_ENDPOINT_MISMATCH")));
            assertFalse(events.stream().anyMatch(e->e.message().contains("Invalid question part identity/support flag")));
        }
    }
}
