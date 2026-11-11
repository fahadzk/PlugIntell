package com.core.docintel.ollama;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class OllamaStructuredClientTest {
    @Test void enforcesSchemaRequestAndRejectsMalformedAndOversizedResponses() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("localhost",0),0);List<String> requests=new ArrayList<>();var reply=new java.util.concurrent.atomic.AtomicReference<>("{\"message\":{\"content\":\"{\\\"ok\\\":true}\"}}");
        server.createContext("/api/chat",e->{requests.add(new String(e.getRequestBody().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));byte[] b=reply.get().getBytes(java.nio.charset.StandardCharsets.UTF_8);e.sendResponseHeaders(200,b.length);e.getResponseBody().write(b);e.close();});server.start();
        try(var client=new OllamaStructuredClient(new OllamaConfig("http://localhost:"+server.getAddress().getPort(),"test",Duration.ofSeconds(2)))){
            assertTrue(client.request("fixed contract","UNTRUSTED SOURCE",Map.of("type","object"),0,2048,128,4096).path("ok").asBoolean());
            var json=new com.fasterxml.jackson.databind.ObjectMapper().readTree(requests.getFirst());assertTrue(json.path("format").isObject());assertFalse(json.path("stream").asBoolean());assertEquals("UNTRUSTED SOURCE",json.path("messages").get(1).path("content").asText());
            reply.set("{\"message\":{\"content\":\"{\\\"x\\\":1,\\\"x\\\":2}\"}}");assertThrows(IllegalArgumentException.class,()->client.request("fixed","source",Map.of(),0,2048,128,4096));
            reply.set("sensitive".repeat(1000));var error=assertThrows(IllegalStateException.class,()->client.request("fixed","source",Map.of(),0,2048,128,100));assertFalse(error.getMessage().contains("sensitive"));
        }finally{server.stop(0);}
    }
    @Test void wholeResponseDeadlineIsBounded() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("localhost",0),0);server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/api/chat",e->{try{Thread.sleep(600);e.sendResponseHeaders(200,2);e.getResponseBody().write("{}".getBytes());}catch(Exception ignored){}finally{e.close();}});server.start();
        try(var client=new OllamaStructuredClient(new OllamaConfig("http://localhost:"+server.getAddress().getPort(),"test",Duration.ofMillis(100)))){assertThrows(IllegalStateException.class,()->client.request("fixed","source",Map.of(),0,2048,128,1000));}finally{server.stop(0);((java.util.concurrent.ExecutorService)server.getExecutor()).shutdownNow();}
    }
    @Test void verifiesModelCapacityBeforeSendingSources() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("localhost",0),0);server.createContext("/api/show",e->{byte[] b="{\"capabilities\":[\"completion\"],\"model_info\":{\"llama.context_length\":8192}}".getBytes();e.sendResponseHeaders(200,b.length);e.getResponseBody().write(b);e.close();});server.start();
        try(var client=new OllamaStructuredClient(new OllamaConfig("http://localhost:"+server.getAddress().getPort(),"test",Duration.ofSeconds(2)))){assertDoesNotThrow(()->client.verifyContextCapacity(4096));assertThrows(IllegalStateException.class,()->client.verifyContextCapacity(16384));}finally{server.stop(0);}
    }
}
