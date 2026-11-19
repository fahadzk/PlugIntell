package com.core.docintel.text2cypher;
import com.core.docintel.neo4j.GraphPolicy;
import com.core.docintel.pipeline.api.*;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class GenericIntentRegressionTest {
    @Test void endpointListsAreNotNativePathsAndFeedbackIdentifiesAllVisibleCandidateErrors(){
        var root=(com.fasterxml.jackson.databind.node.ObjectNode)SemanticRetrievalRegressionTest.route(true,false,false).deepCopy();
        root.put("cypher","MATCH (start:Station {name:$start})-[:NEXT_STOP*1..100]->(end:Station {name:$end}) RETURN collect(nodes([start,end])) AS path, $train AS train");
        ((com.fasterxml.jackson.databind.node.ObjectNode)root.path("intent")).set("anchors",GraphPolicy.JSON.valueToTree(List.of()));
        var draft=SemanticPlan.parse(root,SemanticPlan.hints(SemanticRetrievalRegressionTest.QUESTION));
        var issues=SemanticEvaluation.validate(draft,SemanticPlan.hints(SemanticRetrievalRegressionTest.QUESTION),64);
        assertTrue(issues.stream().anyMatch(i->i.code().equals("INVALID_PATH_CONSTRUCTION")));
        assertTrue(issues.stream().anyMatch(i->i.code().equals("UNBOUNDED_PATH")));
        assertTrue(issues.stream().anyMatch(i->i.code().equals("ENDPOINT_ANCHORS_REQUIRED")));
    }
    @Test void aCorrectedDescriptionClearsTheEarlierMissingComponentFeedback(){
        var missing=Map.<String,Object>of("id","description","kind","DESCRIPTION","status","UNANSWERED");
        var answered=Map.<String,Object>of("id","description","kind","DESCRIPTION","status","ANSWERED");
        var before=new SemanticEvaluation.Evaluation(new GraphAccess.Rows(List.of(),List.of(),List.of(),false),List.of(missing),List.of(),List.of(new SemanticEvaluation.Issue("MISSING_QUESTION_COMPONENT","Missing descriptive component",true)),"EMPTY",0);
        var after=new SemanticEvaluation.Evaluation(new GraphAccess.Rows(List.of(Map.of("description","Stored evidence")),List.of(),List.of(),false),List.of(answered),List.of(),List.of(),"PARTIAL",1001);
        var merged=SemanticEvaluation.merge(before,after,100);
        assertEquals("COMPLETE",merged.status());assertTrue(merged.issues().isEmpty());
    }
    @Test void schemaCompactionPreservesDirectionsPropertyTypesAndUniquenessEvidence(){
        var pattern=Map.of("sourceLabels",List.of("Vertex"),"relationship","LINK","targetLabels",List.of("Vertex"));
        var constraint=Map.of("type","UNIQUENESS","entityType","NODE","labelsOrTypes",List.of("Vertex"),"properties",List.of("key"));
        Map<String,Object> schema=Map.of("nodes",List.of(
                Map.of("labels",List.of("Vertex"),"property","key","types",List.of("String")),
                Map.of("labels",List.of("Vertex"),"property","description","types",List.of("String"))),
                "relationships",List.of(Map.of("type",":`LINK`","property","weight","types",List.of("Double"))),
                "observedPatterns",List.of(pattern),"constraints",List.of(constraint),"patternsAreSampled",true);
        var compact=SchemaView.compact(schema);
        assertEquals(1,((List<?>)compact.get("nodes")).size());
        assertEquals(List.of(pattern),compact.get("observedPatterns"));assertEquals(List.of(constraint),compact.get("constraints"));
        var view=new SchemaView(compact);
        assertTrue(view.has("Vertex","description"));assertTrue(view.has("LINK","weight"));
        assertFalse(view.descriptiveEvidence(List.of("Vertex.key")));
        assertTrue(view.descriptiveEvidence(List.of("Vertex.description")));
    }
    static final class Store implements GraphAccess {
        int reads;List<Map<String,Object>> rows=List.of(Map.of("neighbor","Beta"));
        public void verify(){}
        public Map<String,Object> schema(int limit){return Map.of("nodes",List.of(Map.of("labels",List.of("Vertex"),"properties",Map.of("name","STRING","score","INTEGER"))),"relationships",List.of(Map.of("type","LINK","source","Vertex","target","Vertex")));}
        public Rows query(CypherGuard.Plan plan,int maxRows,int maxBytes){reads++;return new Rows(rows,List.of(),List.of(),false);}
        public void close(){}
    }
    static JsonNode draft(String operation,String query,String column){
        var intent=new LinkedHashMap<String,Object>();intent.put("operation",operation);intent.put("ordered",false);intent.put("intermediateOnly",false);intent.put("direction","OUT");for(String key:List.of("subrouteStart","subrouteEnd","journeyStart","journeyEnd","pathColumn"))intent.put(key,"");intent.put("anchors",List.of());
        intent.put("parts",List.of(SemanticRetrievalRegressionTest.part("result",operation,true,List.of(column),List.of("Vertex.name"))));
        return GraphPolicy.JSON.valueToTree(Map.of("cypher",query,"parameters",List.of(),"intent",intent));
    }
    @Test void directNeighborsUseOneHopAndAreNotExpandedIntoAnOrderedPath(){
        var store=new Store();try(var plugin=SemanticRetrievalRegressionTest.plugin(store,(s,d)->draft("DIRECT","MATCH (a:Vertex)-[:LINK]->(b:Vertex) RETURN b.name AS neighbor LIMIT 100","neighbor"),new ArrayList<>(),2)){
            var result=(RetrievalResult)plugin.execute(new SearchRequest("Which direct neighbors are adjacent to this vertex?",false));assertEquals(1,store.reads);assertEquals("COMPLETE",result.graph().get("semanticStatus"));assertEquals(1,result.chunks().size());
        }
    }
    @Test void aggregationsAcceptZeroAndFilteringRemainsReadOnly(){
        var store=new Store();store.rows=List.of(Map.of("count",0));try(var plugin=SemanticRetrievalRegressionTest.plugin(store,(s,d)->draft("AGGREGATION","MATCH (v:Vertex) RETURN count(v) AS count","count"),new ArrayList<>(),2)){
            var result=(RetrievalResult)plugin.execute(new SearchRequest("How many vertices exist?",false));assertEquals("COMPLETE",result.graph().get("semanticStatus"));assertEquals("DATA",result.graph().get("dataStatus"));assertEquals(1,store.reads);
        }
        store=new Store();store.rows=List.of(Map.of("name","Beta"));try(var plugin=SemanticRetrievalRegressionTest.plugin(store,(s,d)->draft("FILTER","MATCH (v:Vertex) WHERE v.score >= 2 RETURN v.name AS name LIMIT 100","name"),new ArrayList<>(),2)){
            assertEquals("COMPLETE",((RetrievalResult)plugin.execute(new SearchRequest("List vertices with score at least two",false))).graph().get("semanticStatus"));assertEquals(1,store.reads);
        }
    }
    @Test void rejectsMultiHopForAnExplicitDirectNeighborRequestAndNeverExecutesWrites(){
        var store=new Store();try(var plugin=SemanticRetrievalRegressionTest.plugin(store,(s,d)->draft("DIRECT","MATCH (a)-[:LINK*1..64]->(b) RETURN b.name AS neighbor","neighbor"),new ArrayList<>(),0)){
            assertThrows(IllegalArgumentException.class,()->plugin.execute(new SearchRequest("Direct neighbors only",false)));assertEquals(0,store.reads);
        }
        try(var plugin=SemanticRetrievalRegressionTest.plugin(store,(s,d)->draft("LOOKUP","MATCH (n) DETACH DELETE n RETURN n","name"),new ArrayList<>(),2)){
            assertThrows(IllegalArgumentException.class,()->plugin.execute(new SearchRequest("List vertices",false)));assertEquals(0,store.reads);
        }
    }
    @Test void alphabeticListsWithoutNativePathEvidenceAreNotReportedAsAnOrderedRoute(){
        var store=new SemanticRetrievalRegressionTest.RailwayGraph();store.unverifiedList=true;
        var draft=SemanticRetrievalRegressionTest.route(true,false,false);
        try(var plugin=SemanticRetrievalRegressionTest.plugin(store,(s,d)->draft,new ArrayList<>(),0)){
            var result=(RetrievalResult)plugin.execute(new SearchRequest(SemanticRetrievalRegressionTest.QUESTION,false));assertEquals("EMPTY",result.graph().get("semanticStatus"));assertTrue(result.chunks().isEmpty());
        }
    }
    @Test void auditRedactsSensitiveParameterNamesAndSecretValuesInsideIntentRecords(){
        var audit=new RetrievalAudit(Map.of("NEO4J_PASSWORD","private-password","OPENAI_API_KEY","private-api-key"));
        var intent=new SemanticPlan.Intent("LOOKUP",false,false,"BOTH","private-password","","","","",List.of(),List.of(),true);
        assertFalse(audit.json(intent).contains("private-password"));assertFalse(audit.json(Map.of("api_key","anything","filter","private-api-key")).contains("private-api-key"));
        assertFalse(audit.text("RETURN 'private-password'").contains("private-password"));
    }
}
