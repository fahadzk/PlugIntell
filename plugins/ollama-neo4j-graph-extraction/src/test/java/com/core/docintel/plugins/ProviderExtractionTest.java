package com.core.docintel.plugins;

import com.core.docintel.neo4j.GraphPolicy;
import com.core.docintel.pipeline.api.*;
import com.core.docintel.pipeline.api.KnowledgeGraph.Result;
import com.sun.net.httpserver.HttpServer;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ProviderExtractionTest {
    static final String KEY="test-credential-never-return";
    static final class Stub implements AutoCloseable {
        final HttpServer server;final String provider,model;final List<JsonNode> requests=new ArrayList<>();final AtomicInteger calls=new AtomicInteger();
        int status=200,metadataStatus=200,delay;String finish="",padding="";java.util.function.IntFunction<String> output=n->GraphExtractionPluginTest.VALID;
        Stub(String provider,String model)throws Exception{
            this.provider=provider;this.model=model;server=HttpServer.create(new InetSocketAddress("localhost",0),0);
            server.createContext("/",e->{
                try{
                    assertNull(e.getRequestURI().getQuery());assertEquals(KEY,e.getRequestHeaders().getFirst(provider.equals("GEMINI")?"x-goog-api-key":"Authorization").replace("Bearer ",""));
                    String expected=provider.equals("GEMINI")?"/v1beta/models/"+model:"/v1/models/"+model;
                    Object response;
                    if(e.getRequestMethod().equals("GET")){assertEquals(expected,e.getRequestURI().getPath());response=provider.equals("GEMINI")?Map.of("supportedGenerationMethods",List.of("generateContent"),"inputTokenLimit",131072,"outputTokenLimit",16384):Map.of("id",model);}
                    else{
                        assertEquals(provider.equals("GEMINI")?expected+":generateContent":"/v1/chat/completions",e.getRequestURI().getPath());requests.add(GraphPolicy.parse(new String(e.getRequestBody().readAllBytes(),StandardCharsets.UTF_8)));
                        if(delay>0)try{Thread.sleep(delay);}catch(InterruptedException ignored){Thread.currentThread().interrupt();}
                        String content=output.apply(calls.getAndIncrement());
                        if(status!=200)response=Map.of("error",Map.of("status",status==404?"NOT_FOUND":"PERMISSION_DENIED","message",KEY+" private provider error body"));
                        else if(provider.equals("GEMINI"))response=Map.of("candidates",List.of(Map.of("finishReason",finish.isEmpty()?"STOP":finish,"content",Map.of("parts",List.of(Map.of("text",content))))),"padding",padding);
                        else response=Map.of("choices",List.of(Map.of("finish_reason",finish.isEmpty()?"stop":finish,"message",Map.of("content",content))),"padding",padding);
                    }
                    if(e.getRequestMethod().equals("GET")&&metadataStatus!=200)response=Map.of("error",Map.of("status","NOT_FOUND","message",KEY+" metadata failure"));
                    byte[] body=GraphPolicy.encode(response).getBytes(StandardCharsets.UTF_8);e.sendResponseHeaders(e.getRequestMethod().equals("GET")?metadataStatus:status,body.length);e.getResponseBody().write(body);
                }finally{e.close();}
            });server.start();
        }
        Map<String,Object> config(){return new HashMap<>(Map.of("LLM_PROVIDER",provider,provider+"_BASE_URL","http://localhost:"+server.getAddress().getPort(),provider+"_MODEL",model,provider+"_API_KEY",KEY,"NEO4J_PASSWORD","resolved-test-secret"));}
        public void close(){server.stop(0);}
    }
    @Test void distinguishesGeneration404FromMetadata404AndNeverSubstitutesModels()throws Exception{
        try(var stub=new Stub("GEMINI","gemini-2.5-flash-lite");var plugin=new OllamaNeo4jGraphExtractionPlugin(GraphExtractionPluginTest.MESSAGES,new GraphExtractionPluginTest.Store())){
            stub.status=404;plugin.configure(stub.config());
            var error=assertThrows(IllegalStateException.class,()->plugin.execute(GraphExtractionPluginTest.chunk("one")));
            assertTrue(error.getMessage().contains("HTTP 404 (NOT_FOUND) during generateContent"));assertTrue(error.getMessage().contains("metadata lookup succeeded"));assertTrue(error.getMessage().contains("GEMINI_MODEL=gemini-2.5-flash-lite"));assertTrue(error.getMessage().contains("-TestGeneration"));assertFalse(error.getMessage().contains(KEY));assertFalse(error.getMessage().contains("private provider error body"));assertEquals(1,stub.calls.get());
            stub.metadataStatus=404;plugin.configure(stub.config());error=assertThrows(IllegalStateException.class,plugin::preflight);
            assertTrue(error.getMessage().contains("during model metadata lookup"));assertFalse(error.getMessage().contains("metadata lookup succeeded"));assertEquals(1,stub.calls.get());
        }
    }
    @Test void bothCloudProvidersUseSelectedConfigNativeSchemasAndExistingGraphValidation()throws Exception{
        for(String provider:List.of("GEMINI","OPENAI"))try(var stub=new Stub(provider,"future-configurable-model");var plugin=new OllamaNeo4jGraphExtractionPlugin(GraphExtractionPluginTest.MESSAGES,new GraphExtractionPluginTest.Store())){
            var config=stub.config();config.put("OLLAMA_BASE_URL","invalid-unused-provider");plugin.configure(config);plugin.preflight();
            var result=(Result)plugin.execute(GraphExtractionPluginTest.chunk("one"));assertEquals(2,result.extractions().getFirst().entities().size());assertEquals(1,result.writes().getFirst().factsCreated());assertEquals(provider,result.provenance().get("provider"));assertFalse(result.toString().contains(KEY));
            JsonNode request=stub.requests.getFirst();assertFalse(request.toString().contains(KEY));
            if(provider.equals("GEMINI")){assertTrue(request.path("generationConfig").path("responseJsonSchema").isObject());assertEquals("application/json",request.path("generationConfig").path("responseMimeType").asText());}
            else{assertTrue(request.path("response_format").path("json_schema").path("strict").asBoolean());assertEquals(false,request.path("store").asBoolean());assertEquals("future-configurable-model",request.path("model").asText());}
        }
    }
    @Test void openAiOptionalPropertiesAreNullableOnWireAndAbsentInValidatedResult()throws Exception{
        try(var stub=new Stub("OPENAI","example-model");var plugin=new OllamaNeo4jGraphExtractionPlugin(GraphExtractionPluginTest.MESSAGES,new GraphExtractionPluginTest.Store())){
            stub.output=n->GraphExtractionPluginTest.VALID.replace("\"properties\":{}","\"properties\":{\"rating\":null}");var config=stub.config();config.put("KG_PROPERTY_SCHEMAS","{\"rating\":{\"type\":\"integer\"}}");plugin.configure(config);
            var result=(Result)plugin.execute(GraphExtractionPluginTest.chunk("one"));assertTrue(result.extractions().getFirst().entities().getFirst().properties().isEmpty());
            var properties=stub.requests.getFirst().path("response_format").path("json_schema").path("schema").path("properties").path("entities").path("items").path("properties").path("properties");
            assertEquals("rating",properties.path("required").get(0).asText());assertTrue(properties.path("properties").path("rating").path("anyOf").isArray());
        }
    }
    @Test void repairsMalformedOutputAndReturnsExplicitChunkFailures()throws Exception{
        try(var stub=new Stub("GEMINI","gemini-2.5-flash-lite");var plugin=new OllamaNeo4jGraphExtractionPlugin(GraphExtractionPluginTest.MESSAGES,new GraphExtractionPluginTest.Store())){
            stub.output=n->n==0?"{bad":"{\"entities\":[],\"relationships\":[]}";plugin.configure(stub.config());var result=(Result)plugin.execute(GraphExtractionPluginTest.chunk("one"));assertEquals(2,stub.calls.get());assertTrue(result.extractions().getFirst().entities().isEmpty());
            stub.output=n->"{\"entities\":[],\"relationships\":[],\"namespace\":\"admin\"}";var config=stub.config();config.put("KG_REPAIR_RETRIES",0);config.put("KG_FAILURE_POLICY","CONTINUE");plugin.configure(config);result=(Result)plugin.execute(GraphExtractionPluginTest.chunk("invalid"));assertEquals(1,result.failures().size());assertTrue(result.writes().isEmpty());
        }
    }
    @Test void authQuotaIncompleteOversizedAndTimeoutFailuresNeverPersistOrExposeKeys()throws Exception{
        for(String scenario:List.of("auth","quota","incomplete","bytes","timeout"))try(var stub=new Stub("GEMINI","gemini-2.5-flash");var plugin=new OllamaNeo4jGraphExtractionPlugin(GraphExtractionPluginTest.MESSAGES,new GraphExtractionPluginTest.Store())){
            var config=stub.config();config.put("KG_REPAIR_RETRIES",0);
            switch(scenario){case "auth"->stub.status=403;case "quota"->stub.status=429;case "incomplete"->stub.finish="MAX_TOKENS";case "bytes"->{stub.padding="x".repeat(4000);config.put("KG_RESPONSE_BYTES",1024);}case "timeout"->{stub.delay=1500;config.put("GEMINI_TIMEOUT_SECONDS",1);}}
            plugin.configure(config);var error=assertThrows(IllegalStateException.class,()->plugin.execute(GraphExtractionPluginTest.chunk("one")));assertFalse(error.toString().contains(KEY));assertFalse(error.toString().contains("private provider error body"));
            if(scenario.equals("auth"))assertTrue(error.getMessage().contains("403"));if(scenario.equals("quota"))assertTrue(error.getMessage().contains("429"));
        }
    }
    @Test void requiredCredentialsModelAndSafeUrlsAreValidatedOnlyForTheChosenProvider()throws Exception{
        try(var stub=new Stub("OPENAI","any-model");var plugin=new OllamaNeo4jGraphExtractionPlugin(GraphExtractionPluginTest.MESSAGES,new GraphExtractionPluginTest.Store())){
            for(String key:List.of("OPENAI_MODEL","OPENAI_API_KEY")){var config=stub.config();config.remove(key);assertThrows(IllegalArgumentException.class,()->plugin.configure(config));}
            for(String bad:List.of("http://remote.example","https://user:private-uri-value@example.com","https://example.com?key=private-uri-value")){var config=stub.config();config.put("OPENAI_BASE_URL",bad);var error=assertThrows(IllegalArgumentException.class,()->plugin.configure(config));assertFalse(error.getMessage().contains("private-uri-value"));}
            var config=stub.config();config.put("LLM_PROVIDER","UNSUPPORTED");assertThrows(IllegalArgumentException.class,()->plugin.configure(config));
        }
    }
}
