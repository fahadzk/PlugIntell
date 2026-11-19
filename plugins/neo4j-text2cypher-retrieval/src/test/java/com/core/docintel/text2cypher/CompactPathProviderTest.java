package com.core.docintel.text2cypher;

import com.core.docintel.neo4j.GraphPolicy;
import com.core.docintel.pipeline.api.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.util.*;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

class CompactPathProviderTest {
    @Test void focusedInputSeparatesSubrouteFromJourneyAndUsesActualSchemaDirections(){
        var schema=new Graph().schema(256);String input=GraphPolicy.encode(Map.of("question",SemanticRetrievalRegressionTest.QUESTION,"questionHints",SemanticPlan.hints(SemanticRetrievalRegressionTest.QUESTION),"schema",schema,"resolvedAnchors",List.of(),"feedback",Map.of()));
        var focused=GraphPolicy.parse(PathQueryContract.focusedData(input));
        assertFalse(focused.has("question"));assertFalse(focused.has("questionHints"));
        assertEquals("Lahore",focused.path("retrievalTask").path("pathStart").asText());assertEquals("Multan",focused.path("retrievalTask").path("pathEnd").asText());assertEquals("Tezgam",focused.path("retrievalTask").path("subject").asText());
        assertFalse(focused.toString().contains("Rawalpindi"));assertFalse(focused.toString().contains("Karachi"));
        assertTrue(focused.path("directedSchemaPatterns").toString().contains("(Train)-[:STOPS_AT]->(Station)"));assertTrue(focused.path("directedSchemaPatterns").toString().contains("(Station)-[:NEXT_STOP]->(Station)"));
        var unrelated=GraphPolicy.parse("{\"observedPatterns\":[{\"sourceLabels\":[\"Project\"],\"relationship\":\"OWNS\",\"targetLabels\":[\"Task\"]},{\"sourceLabels\":[\"Task\"],\"relationship\":\"DEPENDS_ON\",\"targetLabels\":[\"Task\"]}]}");
        assertEquals(List.of("(Project)-[:OWNS]->(Task)","(Task)-[:DEPENDS_ON]->(Task)"),PathQueryContract.directedPatterns(unrelated));
        var unscoped=GraphPolicy.parse(PathQueryContract.focusedData(GraphPolicy.encode(Map.of("question","Find the ordered path between Alpha and Beta.","schema",schema,"feedback",Map.of(),"resolvedAnchors",List.of()))));
        assertEquals("ORDERED_PATH",unscoped.path("retrievalTask").path("operation").asText());assertFalse(unscoped.path("retrievalTask").path("subjectMatchRequired").asBoolean());
        assertTrue(PathQueryContract.prompt("",64,100,false).contains("No subject was specified"));assertFalse(PathQueryContract.prompt("",64,100,false).contains("CONTAINS"));
        assertTrue(PathQueryContract.prompt("",64,100,true).contains("Do not omit the subject MATCH"));assertFalse(PathQueryContract.prompt("",64,100,true).contains("[:REL*"));
    }
    static JsonNode observed(){return GraphPolicy.JSON.valueToTree(Map.of("cypher","MATCH (train:Train {name:$subjectTrainName})-[:STOPS_AT]->(startStation:Station {name:$betweenStart})-[:NEXT_STOP*1..64]->(endStation:Station {name:$betweenEnd}) RETURN nodes($path) AS pathColumn, COUNT(*) AS stopCount","parameters",List.of(Map.of("name","betweenStart","value","Lahore"),Map.of("name","betweenEnd","value","Multan"),Map.of("name","subjectTrainName","value","Tezgam"))));}
    static class Graph implements GraphAccess {
        final SemanticRetrievalRegressionTest.RailwayGraph base=new SemanticRetrievalRegressionTest.RailwayGraph();int executions;
        public void verify(){}public void close(){}public Map<String,Object> schema(int limit){return base.schema;}
        public Rows query(CypherGuard.Plan plan,int maxRows,int maxBytes){
            if(plan.cypher().contains("elementId(n) AS elementId"))return base.query(plan,maxRows,maxBytes);
            executions++;assertTrue(plan.cypher().contains("MATCH generatedPath="));assertFalse(plan.cypher().contains("$path"));assertTrue(plan.cypher().contains("RETURN generatedPath AS pathColumn"));
            assertEquals(Map.of("subjectTrainName","Tezgam","betweenStart","Lahore Jn.","betweenEnd","Multan Cantt"),plan.parameters());
            var nativeRows=base.query(new CypherGuard.Plan(plan.cypher(),Map.of("train",plan.parameters().get("subjectTrainName"),"start",plan.parameters().get("betweenStart"),"end",plan.parameters().get("betweenEnd"))),maxRows,maxBytes);
            return new Rows(List.of(Map.of("pathColumn",nativeRows.rows().getFirst().get("path"),"stopCount",1)),nativeRows.nodes(),nativeRows.edges(),false,List.of(new NativePath(0,"pathColumn",nativeRows.paths().getFirst().nodes(),nativeRows.paths().getFirst().edges())));
        }
    }
    @Test void derivesValuesAndUnsupportedDescriptionLocallyFromCompactOutput(){
        var graph=new Graph();var raw=observed();var enriched=PathQueryContract.deriveIntent(raw,SemanticPlan.hints(SemanticRetrievalRegressionTest.QUESTION),new SchemaView(graph.schema(256)));
        assertFalse(raw.has("intent"));assertEquals("Lahore",enriched.path("intent").path("subrouteStart").asText());assertEquals("Rawalpindi",enriched.path("intent").path("journeyStart").asText());
        assertEquals(3,enriched.path("intent").path("anchors").size());assertTrue(enriched.path("intent").path("anchors").toString().contains("\"mention\":\"Tezgam\""));
        assertFalse(enriched.path("intent").path("parts").get(1).path("supported").asBoolean());
        var normalized=CandidateNormalizer.normalize(enriched,new SchemaView(graph.schema(256)));
        assertFalse(normalized.candidate().path("cypher").asText().contains("nodes($path)"));
        assertDoesNotThrow(()->SemanticPlan.parse(normalized.candidate(),SemanticPlan.hints(SemanticRetrievalRegressionTest.QUESTION)));
    }
    @Test void allProviderAdaptersRequestOnlyCypherAndParametersAndRetrieveOrderedNativePath()throws Exception{
        var server=HttpServer.create(new InetSocketAddress("localhost",0),0);var contracts=new ArrayList<JsonNode>();var prompts=new ArrayList<String>();var modelInputs=new ArrayList<JsonNode>();String plan=GraphPolicy.encode(observed());
        server.createContext("/",exchange->{
            String path=exchange.getRequestURI().getPath();String input=new String(exchange.getRequestBody().readAllBytes(),UTF_8);JsonNode request=input.isBlank()?GraphPolicy.JSON.createObjectNode():GraphPolicy.parse(input);Object response;
            if(path.equals("/api/show"))response=Map.of("capabilities",List.of("completion"),"model_info",Map.of("fixture.context_length",32768));
            else if(path.equals("/api/chat")){contracts.add(request.path("format"));prompts.add(request.path("messages").get(0).path("content").asText());modelInputs.add(GraphPolicy.parse(request.path("messages").get(1).path("content").asText()));response=Map.of("message",Map.of("content",plan));}
            else if(path.equals("/v1/chat/completions")){contracts.add(request.path("response_format").path("json_schema").path("schema"));prompts.add(request.path("messages").get(0).path("content").asText());modelInputs.add(GraphPolicy.parse(request.path("messages").get(1).path("content").asText()));response=Map.of("choices",List.of(Map.of("finish_reason","stop","message",Map.of("content",plan))));}
            else if(path.endsWith(":generateContent")){contracts.add(request.path("generationConfig").path("responseJsonSchema"));prompts.add(request.path("systemInstruction").path("parts").get(0).path("text").asText());modelInputs.add(GraphPolicy.parse(request.path("contents").get(0).path("parts").get(0).path("text").asText()));response=Map.of("candidates",List.of(Map.of("finishReason","STOP","content",Map.of("parts",List.of(Map.of("text",plan))))));}
            else if(path.startsWith("/v1/models/"))response=Map.of("id","fixture");
            else response=Map.of("supportedGenerationMethods",List.of("generateContent"),"inputTokenLimit",32768,"outputTokenLimit",8192);
            byte[] body=GraphPolicy.encode(response).getBytes(UTF_8);exchange.sendResponseHeaders(200,body.length);try(var stream=exchange.getResponseBody()){stream.write(body);}
        });server.start();
        try{
            for(String provider:List.of("OLLAMA","OPENAI","GEMINI")){
                var config=new HashMap<>(Text2CypherPluginTest.config());config.put("LLM_PROVIDER",provider);config.put(provider+"_BASE_URL","http://localhost:"+server.getAddress().getPort());config.put(provider+"_TIMEOUT_SECONDS",5);
                config.put(provider.equals("OLLAMA")?"OLLAMA_CHAT_MODEL":provider+"_MODEL","fixture");if(!provider.equals("OLLAMA"))config.put(provider+"_API_KEY","private-fixture-key");
                var graph=new Graph();try(var plugin=new Neo4JText2CypherRetrievalPlugin(PluginMessages.noop(),graph,null)){
                    plugin.configure(config);plugin.preflight();var result=(RetrievalResult)plugin.execute(new SearchRequest(SemanticRetrievalRegressionTest.QUESTION,false));
                    assertEquals(1,graph.executions);assertEquals(SemanticRetrievalRegressionTest.INTERMEDIATE,SemanticRetrievalRegressionTest.entities(result));assertEquals("PARTIAL",result.graph().get("semanticStatus"));assertEquals(1,((List<?>)result.graph().get("unansweredParts")).size());
                }
            }
            assertEquals(3,contracts.size());for(var schema:contracts){assertEquals(2,schema.path("properties").size());assertFalse(schema.path("properties").has("intent"));}
            assertTrue(prompts.stream().allMatch(p->p.contains("Do not generate intent")&&p.contains("path is a query variable")));
            assertEquals(3,modelInputs.size());for(var input:modelInputs){assertEquals("Lahore",input.path("retrievalTask").path("pathStart").asText());assertFalse(input.has("question"));assertFalse(input.has("questionHints"));assertFalse(input.path("directedSchemaPatterns").isEmpty());}
        }finally{server.stop(0);}
    }
    @Test void neverDropsAMissingNonPathParameterOrUsesAProvidedPathValueAsNativeEvidence(){
        var graph=new Graph();var root=(com.fasterxml.jackson.databind.node.ObjectNode)observed().deepCopy();root.put("cypher",root.path("cypher").asText()+", $missing AS required");
        var enriched=PathQueryContract.deriveIntent(root,SemanticPlan.hints(SemanticRetrievalRegressionTest.QUESTION),new SchemaView(graph.schema(256)));
        var normalized=CandidateNormalizer.normalize(enriched,new SchemaView(graph.schema(256)));var invalid=normalized.candidate();assertThrows(IllegalArgumentException.class,()->SemanticPlan.parse(invalid,SemanticPlan.hints(SemanticRetrievalRegressionTest.QUESTION)));
        root=(com.fasterxml.jackson.databind.node.ObjectNode)observed().deepCopy();((com.fasterxml.jackson.databind.node.ArrayNode)root.get("parameters")).addObject().put("name","path").put("value","fabricated");
        enriched=PathQueryContract.deriveIntent(root,SemanticPlan.hints(SemanticRetrievalRegressionTest.QUESTION),new SchemaView(graph.schema(256)));normalized=CandidateNormalizer.normalize(enriched,new SchemaView(graph.schema(256)));
        assertTrue(normalized.candidate().path("cypher").asText().contains("nodes($path)"));
    }
}
