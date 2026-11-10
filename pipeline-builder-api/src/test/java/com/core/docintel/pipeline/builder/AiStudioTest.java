package com.core.docintel.pipeline.builder;
import com.sun.net.httpserver.HttpServer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.core.docintel.pipeline.builder.helpers.LayaPluginSelector;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AiStudioTest {
    private final ObjectMapper json=new ObjectMapper();
    @Test void validatesConfiguredUrl() {
        assertDoesNotThrow(()->new LayaPluginSelector(" 'http://localhost:11434/' ",1,""));
        for(String value:List.of("", "not a URL", "\"http://localhost:11434", "ftp://localhost:11434", "http://localhost:11434?token=private")) {
            var error=assertThrows(IllegalArgumentException.class,()->new LayaPluginSelector(value,1,""));
            assertTrue(error.getMessage().contains("LAYA_BASE_URL"));
            assertFalse(error.getMessage().contains("private"));
        }
    }
    @Test void installedCatalogAndMissingProvider() throws Exception {
        var received=new ArrayList<String>();
        var server=stub(received,false);
        try {
            var controller=new AiStudioController(new PluginCatalog("../plugins"),"  \"http://localhost:"+server.getAddress().getPort()+"/\"  ",2,"");
            String prompt="Extract PDFs, chunk and embed with Ollama, store in Neo4j and answer questions.";
            var result=body(controller,prompt);
            assertEquals(List.of("Text extraction","Chunking","Embedding","Vector write"),steps(result,"ingestion").stream().map(s->s.get("step")).toList());
            assertEquals(List.of("Retrieval","Answer generation"),steps(result,"query").stream().map(s->s.get("step")).toList());
            assertTrue((Boolean)((Map<?,?>)result.get("ingestion")).get("complete"));
            assertTrue((Boolean)((Map<?,?>)result.get("query")).get("complete"));
            assertEquals("neo4j-writer",steps(result,"ingestion").get(3).get("id"));
            assertEquals("neo4j-retrieval",steps(result,"query").getFirst().get("id"));
            assertFalse(received.isEmpty());
            for(String request:received){var tree=json.readTree(request);assertEquals(prompt,tree.path("state").asText());}
            var missing=body(controller,"Extract PDF and use ChromaDB with Ollama for RAG");
            assertFalse((Boolean)((Map<?,?>)missing.get("ingestion")).get("complete"));
            assertFalse((Boolean)((Map<?,?>)missing.get("query")).get("complete"));
            assertTrue(steps(missing,"ingestion").get(3).get("missing").toString().contains("chromadb"));
            assertTrue(steps(missing,"query").getFirst().get("missing").toString().contains("chromadb"));
        } finally {server.stop(0);}
    }
    @Test void explicitPluginNamesChangeTheRecommendation() throws Exception {
        // Even a stub that always picks its first option cannot override explicit plugin names.
        var received=new ArrayList<String>();var server=stub(received,false);
        try {
            var controller=new AiStudioController(new PluginCatalog("../plugins"),"http://localhost:"+server.getAddress().getPort(),2,"");
            var result=body(controller,"Create a project History of Middle East, ingest documents using Tika Document Parser and LangChain4j Splitter, use neo4j to store embeddings and return the top 5 results to my network Ollama");
            assertEquals(List.of("tika-document-parser","langchain4j-splitter","ollama-embedding","neo4j-writer"),steps(result,"ingestion").stream().map(s->s.get("id")).toList());
            assertEquals(List.of("neo4j-retrieval","ollama-answer"),steps(result,"query").stream().map(s->s.get("id")).toList());
            assertTrue((Boolean)((Map<?,?>)result.get("ingestion")).get("complete"));
            var other=body(controller,"Use PDF OCR to Text and Static Character Splitter with Ollama and Neo4j");
            assertEquals("pdf-ocr-to-text",steps(other,"ingestion").get(0).get("id"));
            assertEquals("static-splitter",steps(other,"ingestion").get(1).get("id"));
            var aliases=body(controller,"Use Tika and LangChain4j with Ollama and Neo4j");
            assertEquals("tika-document-parser",steps(aliases,"ingestion").get(0).get("id"));
            assertEquals("langchain4j-splitter",steps(aliases,"ingestion").get(1).get("id"));
            assertTrue(received.isEmpty(),"All these prompts explicitly constrain every step to a single installed plugin.");
        } finally {server.stop(0);}
    }
    @Test void acceptsDifferentEligibleLayaChoices() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("localhost",0),0);
        server.createContext("/v1/systemone",exchange->{
            var body=json.readTree(exchange.getRequestBody());var questions=body.path("questions");String step=questions.fieldNames().next();
            String chosen=switch(step) {case "extraction"->"tika-document-parser";case "chunking"->"langchain4j-splitter";default->questions.path(step).path("criteria").fieldNames().next();};
            assertTrue(questions.path(step).path("criteria").has(chosen));
            byte[] bytes=json.writeValueAsBytes(Map.of("answers",Map.of(step,Map.of("choice",chosen))));exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });server.start();
        try {
            var result=body(new AiStudioController(new PluginCatalog("../plugins"),"http://localhost:"+server.getAddress().getPort(),2,""),"Ingest documents and answer questions using Ollama and Neo4j");
            assertEquals("tika-document-parser",steps(result,"ingestion").get(0).get("id"));
            assertEquals("langchain4j-splitter",steps(result,"ingestion").get(1).get("id"));
        } finally {server.stop(0);}
    }
    @Test void invalidChoiceAndUnreachableAreActionable() throws Exception {
        var server=stub(new ArrayList<>(),true);int port=server.getAddress().getPort();
        var controller=new AiStudioController(new PluginCatalog("../plugins"),"http://localhost:"+port,1,"");
        try {var response=controller.recommend(new AiStudioController.SuggestRequest("PDF RAG"));assertEquals(502,response.code());assertTrue(response.body().toString().contains("unknown or incompatible"));}
        finally {server.stop(0);}
        var response=controller.recommend(new AiStudioController.SuggestRequest("PDF RAG"));
        assertEquals(502,response.code());assertTrue(response.body().toString().contains("LAYA_BASE_URL"));
        assertEquals(400,controller.recommend(new AiStudioController.SuggestRequest(" ")).code());
    }
    private HttpServer stub(List<String> received,boolean invalid) throws Exception {
        var server=HttpServer.create(new InetSocketAddress("localhost",0),0);
        server.createContext("/v1/systemone",exchange->{
            String request=new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);received.add(request);
            var questions=json.readTree(request).path("questions");String key=questions.fieldNames().next();
            assertEquals("choice",questions.path(key).path("type").asText());
            String id=invalid?"unknown":questions.path(key).path("criteria").fieldNames().next();
            byte[] bytes=json.writeValueAsBytes(Map.of("answers",Map.of(key,Map.of("choice",id))));
            exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });server.start();return server;
    }
    @SuppressWarnings("unchecked") private Map<String,Object> body(AiStudioController c,String prompt){var response=c.recommend(new AiStudioController.SuggestRequest(prompt));assertEquals(200,response.code(),String.valueOf(response.body()));return (Map<String,Object>)response.body();}
    @SuppressWarnings("unchecked") private List<Map<String,Object>> steps(Map<String,Object> r,String lane){return (List<Map<String,Object>>)((Map<?,?>)r.get(lane)).get("steps");}
}
