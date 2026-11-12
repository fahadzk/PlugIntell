package com.plugintell.graph;
import com.core.docintel.pipeline.api.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
class GlinerGraphExtractionPluginTest {
    @Test void ontologyRequestsUseCustomPromptOrDefaultForEmptyInput()throws Exception{
        var server=HttpServer.create(new InetSocketAddress("localhost",0),0);
        var received=new ArrayList<String>();
        server.createContext("/api/chat",e->{
            var request=Json.object(Json.parse(new String(e.getRequestBody().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8)));
            var messages=(List<?>)request.get("messages");received.add((String)Json.object(messages.getFirst()).get("content"));
            byte[] response=Json.MAPPER.writeValueAsBytes(Json.obj("message",Json.obj("content",Json.MAPPER.writeValueAsString(GraphSchema.defaults().toJson()))));
            e.sendResponseHeaders(200,response.length);try(var out=e.getResponseBody()){out.write(response);}
        });
        server.createContext("/extract",e->{byte[] response="{\"entities\":[],\"relations\":[]}".getBytes();e.sendResponseHeaders(200,response.length);try(var out=e.getResponseBody()){out.write(response);}});
        server.start();
        try{
            String base="http://localhost:"+server.getAddress().getPort();
            for(String prompt:List.of("","  \n\t ","Which organizations and historical events matter?")){
                var plugin=new GlinerGraphExtractionPlugin();plugin.configure(Map.of("GRAPH_EXTRACT_URL",base,"GRAPH_LLM_URL",base,"GRAPH_LLM_MODEL","stub","GRAPH_PROMPT",prompt));
                var graph=(ExtractedGraph)plugin.execute(new TextChunk("c","d","s",1,0,"A historical event."));
                assertEquals("llm",graph.schema().get("source"));
                assertEquals(prompt.isBlank()?GlinerGraphExtractionPlugin.DEFAULT_PROMPT:prompt,received.getLast());
            }
            assertEquals(3,received.size());
        }finally{server.stop(0);}
    }
    @Test void mergesDuplicateTextsPreservesEndpointsAndReportsProgress()throws Exception{
        var server=HttpServer.create(new InetSocketAddress("localhost",0),0);var calls=new AtomicInteger();
        server.createContext("/extract",e->{
            calls.incrementAndGet();var request=Json.object(Json.parse(new String(e.getRequestBody().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8)));
            assertEquals("Alice founded Acme.",request.get("text"));assertTrue(request.containsKey("relations"));
            byte[] body="""
                {"entities":[{"id":"a","text":"Alice","label":"person","score":0.9},{"id":"b","text":"Acme","label":"organization"}],
                 "relations":[{"type":"founded","head":"a","tail":"b"},{"type":"invalid","head":"a","tail":"b"}]}
                """.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            e.sendResponseHeaders(200,body.length);try(var out=e.getResponseBody()){out.write(body);}
        });server.start();
        try {
            var messages=new ArrayList<PluginMessage>();var plugin=new GlinerGraphExtractionPlugin(messages::add);
            plugin.configure(Map.of("GRAPH_EXTRACT_URL","http://localhost:"+server.getAddress().getPort()));
            var chunks=List.of(new TextChunk("c1","d","sample",1,0,"Alice founded Acme."),new TextChunk("c2","d","sample",1,1,"Alice founded Acme."));
            var result=(ExtractedGraph)plugin.execute(new ChunkCollection("d",chunks));
            assertEquals(1,calls.get());assertEquals(2,result.entities().size());assertEquals(1,result.relationships().size());
            var edge=result.relationships().getFirst();var ids=result.entities().stream().map(n->n.get("id")).toList();
            assertTrue(ids.contains(edge.get("source_id")));assertTrue(ids.contains(edge.get("target_id")));assertEquals(Set.of("c1","c2"),edge.get("chunk_ids"));
            assertEquals(2,result.metadata().get("relationships_dropped"));assertEquals(true,result.metadata().get("complete"));
            assertTrue(messages.stream().anyMatch(m->m.code().equals("progress")));assertEquals(chunks,result.chunks());
        }finally{server.stop(0);}
    }
    @Test void httpFailuresProduceIncompleteResultWithoutProviderBody()throws Exception{
        var server=HttpServer.create(new InetSocketAddress("localhost",0),0);
        server.createContext("/extract",e->{byte[] body="private error".getBytes();e.sendResponseHeaders(403,body.length);try(var out=e.getResponseBody()){out.write(body);}});server.start();
        try{var plugin=new GlinerGraphExtractionPlugin();plugin.configure(Map.of("GRAPH_EXTRACT_URL","http://localhost:"+server.getAddress().getPort()));
            var result=(ExtractedGraph)plugin.execute(new TextChunk("c","d","s",1,0,"Alice"));
            assertEquals(false,result.metadata().get("complete"));assertEquals(List.of("c"),result.metadata().get("failed_chunk_ids"));assertFalse(result.toString().contains("private error"));
        }finally{server.stop(0);}
    }
    @Test void rejectsUnsafeEndpointsAndAmbiguousAliases(){
        assertThrows(IllegalArgumentException.class,()->new GlinerGraphExtractionPlugin().configure(Map.of("GRAPH_EXTRACT_URL","http://remote.example")));
        assertThrows(IllegalArgumentException.class,()->new AliasRegistry("{\"Person\":{\"Alice\":[\"a\"],\"Bob\":[\"a\"]}}",GraphSchema.defaults()));
    }
}
