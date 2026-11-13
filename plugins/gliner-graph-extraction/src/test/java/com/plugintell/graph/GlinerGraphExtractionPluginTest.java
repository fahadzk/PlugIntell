package com.plugintell.graph;
import com.core.docintel.pipeline.api.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
class GlinerGraphExtractionPluginTest {
    @Test void disablingOntologyMakesNoLlmRequestsAndStillExtractsRelationships()throws Exception{
        var server=HttpServer.create(new InetSocketAddress("localhost",0),0);var llmCalls=new AtomicInteger();
        server.createContext("/api/chat",e->{llmCalls.incrementAndGet();e.sendResponseHeaders(500,-1);e.close();});
        server.createContext("/extract",e->{
            e.getRequestBody().readAllBytes();byte[] body="""
                {"entities":[{"id":"p","text":"Alice","label":"person"},{"id":"o","text":"Acme","label":"organization"}],
                 "relations":[{"type":"founded","head":"p","tail":"o"}]}
                """.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            e.sendResponseHeaders(200,body.length);try(var out=e.getResponseBody()){out.write(body);}
        });server.start();
        try{
            String base="http://localhost:"+server.getAddress().getPort();var events=new ArrayList<PluginMessage>();
            var plugin=new GlinerGraphExtractionPlugin(events::add);
            plugin.configure(Map.of("GRAPH_EXTRACT_URL",base,"GRAPH_LLM_URL",base,"GRAPH_GENERATE_ONTOLOGY",false));
            var result=(ExtractedGraph)plugin.execute(new TextChunk("c","d","s",1,0,"Alice founded Acme."));
            assertEquals(0,llmCalls.get());assertEquals("default",result.schema().get("source"));assertEquals(1,result.relationships().size());
            assertTrue(events.stream().anyMatch(e->e.message().contains("LLM ontology generation disabled")));
            assertFalse(events.stream().anyMatch(e->e.message().contains("Sending prompt to generate")));
            plugin.configure(Map.of("GRAPH_EXTRACT_URL",base,"GRAPH_LLM_URL",base,"GRAPH_GENERATE_ONTOLOGY",false,"GRAPH_SCHEMA_JSON",Json.MAPPER.writeValueAsString(GraphSchema.defaults().toJson())));
            var supplied=(ExtractedGraph)plugin.execute(new TextChunk("c","d","s",1,0,"Alice founded Acme."));
            assertEquals("provided",supplied.schema().get("source"));assertEquals(0,llmCalls.get());
        }finally{server.stop(0);}
    }
    @Test void ontologyTimeoutIsReportedSeparatelyFromSuccessfulExtraction()throws Exception{
        var server=HttpServer.create(new InetSocketAddress("localhost",0),0);
        var pool=java.util.concurrent.Executors.newCachedThreadPool();server.setExecutor(pool);
        server.createContext("/api/chat",e->{
            e.getRequestBody().readAllBytes();
            try{Thread.sleep(2000);e.sendResponseHeaders(200,0);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}finally{e.close();}
        });
        server.createContext("/extract",e->{
            e.getRequestBody().readAllBytes();byte[] body="{\"entities\":[],\"relations\":[]}".getBytes();
            e.sendResponseHeaders(200,body.length);try(var out=e.getResponseBody()){out.write(body);}
        });server.start();
        try{
            String base="http://localhost:"+server.getAddress().getPort();var messages=new ArrayList<PluginMessage>();
            var plugin=new GlinerGraphExtractionPlugin(messages::add);
            plugin.configure(Map.of("GRAPH_EXTRACT_URL",base,"GRAPH_LLM_URL",base,"GRAPH_LLM_MODEL","stub","GRAPH_TIMEOUT_SECONDS",5,"GRAPH_ONTOLOGY_TIMEOUT_SECONDS",1));
            var result=(ExtractedGraph)plugin.execute(new TextChunk("c","d","s",1,0,"A historical event."));
            assertEquals("TIMEOUT",result.metadata().get("ontology_fallback_reason"));
            assertTrue(((Number)result.metadata().get("ontology_duration_ms")).longValue()>=700);
            assertEquals(0,result.metadata().get("chunks_failed"));assertEquals(true,result.metadata().get("complete"));
            assertTrue(messages.stream().anyMatch(m->m.toString().contains("TIMEOUT")));
            assertOntologyLogged(messages,GraphSchema.defaults(),"Fallback");
        }finally{server.stop(0);pool.shutdownNow();}
    }
    @Test void invalidOntologySchemaIsNotReportedAsTimeout()throws Exception{
        var server=HttpServer.create(new InetSocketAddress("localhost",0),0);
        server.createContext("/api/chat",e->{
            e.getRequestBody().readAllBytes();byte[] body="{\"message\":{\"content\":\"{}\"}}".getBytes();
            e.sendResponseHeaders(200,body.length);try(var out=e.getResponseBody()){out.write(body);}
        });
        server.createContext("/extract",e->{e.getRequestBody().readAllBytes();byte[] body="{\"entities\":[]}".getBytes();e.sendResponseHeaders(200,body.length);try(var out=e.getResponseBody()){out.write(body);}});
        server.start();
        try{
            String base="http://localhost:"+server.getAddress().getPort();var plugin=new GlinerGraphExtractionPlugin();
            plugin.configure(Map.of("GRAPH_EXTRACT_URL",base,"GRAPH_LLM_URL",base,"GRAPH_LLM_MODEL","stub"));
            var result=(ExtractedGraph)plugin.execute(new TextChunk("c","d","s",1,0,"A historical event."));
            assertEquals("INVALID_ONTOLOGY_SCHEMA",result.metadata().get("ontology_fallback_reason"));
            assertEquals(1,result.metadata().get("chunks_missing_relation_field"));
            assertTrue(result.metadata().get("warnings").toString().contains("omitted relations/relationships"));
        }finally{server.stop(0);}
    }
    @Test void ontologyRequestsUseCustomPromptOrDefaultForEmptyInput()throws Exception{
        var server=HttpServer.create(new InetSocketAddress("localhost",0),0);
        var received=new ArrayList<String>();
        var requests=new ArrayList<Map<String,Object>>();
        server.createContext("/api/chat",e->{
            var request=Json.object(Json.parse(new String(e.getRequestBody().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8)));
            requests.add(request);
            var messages=(List<?>)request.get("messages");received.add((String)Json.object(messages.getFirst()).get("content"));
            byte[] response=Json.MAPPER.writeValueAsBytes(Json.obj("message",Json.obj("content",Json.MAPPER.writeValueAsString(GraphSchema.defaults().toJson()))));
            e.sendResponseHeaders(200,response.length);try(var out=e.getResponseBody()){out.write(response);}
        });
        server.createContext("/extract",e->{byte[] response="{\"entities\":[],\"relations\":[]}".getBytes();e.sendResponseHeaders(200,response.length);try(var out=e.getResponseBody()){out.write(response);}});
        server.start();
        try{
            String base="http://localhost:"+server.getAddress().getPort();
            for(String prompt:List.of("","  \n\t ","Which organizations and historical events matter?")){
                var events=new ArrayList<PluginMessage>();
                var plugin=new GlinerGraphExtractionPlugin(events::add);plugin.configure(Map.of("GRAPH_EXTRACT_URL",base,"GRAPH_LLM_URL",base,"GRAPH_LLM_MODEL","stub","GRAPH_PROMPT",prompt,"GRAPH_LLM_API_KEY","test-private-token"));
                var graph=(ExtractedGraph)plugin.execute(new TextChunk("c","d","s",1,0,"A historical event."));
                assertEquals("llm",graph.schema().get("source"));
                assertOntologyLogged(events,GraphSchema.defaults(),"Generated");
                assertTrue(received.getLast().startsWith(GlinerGraphExtractionPlugin.DEFAULT_PROMPT));
                if(!prompt.isBlank())assertTrue(received.getLast().endsWith(prompt));
                String logged=events.stream().map(PluginMessage::message).filter(m->m.startsWith("Sending prompt to generate")).findFirst().orElseThrow();
                assertTrue(logged.contains(base+"/api/chat"));
                assertEquals(requests.getLast(),Json.parse(logged.substring(logged.indexOf(" | request: ")+12)));
                assertFalse(events.toString().contains("test-private-token"));
            }
            assertEquals(3,received.size());
        }finally{server.stop(0);}
    }
    @Test void mergesDuplicateTextsPreservesEndpointsAndReportsProgress()throws Exception{
        var server=HttpServer.create(new InetSocketAddress("localhost",0),0);var calls=new AtomicInteger();
        server.createContext("/extract",e->{
            calls.incrementAndGet();var request=Json.object(Json.parse(new String(e.getRequestBody().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8)));
            assertEquals("Alice founded Acme.",request.get("text"));assertTrue(request.containsKey("relations"));
            assertEquals("beam",request.get("optimizer"));assertEquals(16,request.get("beam_size"));
            assertEquals(0.3,((Number)request.get("entity_threshold")).doubleValue());
            assertEquals(0.05,((Number)request.get("candidate_threshold")).doubleValue());
            assertEquals(0.05,((Number)request.get("relation_role_threshold")).doubleValue());
            assertTrue(((List<?>)request.get("relations")).stream().map(Json::object).anyMatch(r->"founded".equals(r.get("name"))&&"person".equals(r.get("head"))&&"organization".equals(r.get("tail"))));
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
            assertOntologyLogged(messages,GraphSchema.defaults(),"Default");
            var edge=result.relationships().getFirst();var ids=result.entities().stream().map(n->n.get("id")).toList();
            assertTrue(ids.contains(edge.get("source_id")));assertTrue(ids.contains(edge.get("target_id")));assertEquals(Set.of("c1","c2"),edge.get("chunk_ids"));
            assertEquals(2,result.metadata().get("relationships_dropped"));assertEquals(true,result.metadata().get("complete"));
            assertEquals(4,result.metadata().get("relationships_received"));assertEquals(0,result.metadata().get("chunks_missing_relation_field"));
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
    private static void assertOntologyLogged(List<PluginMessage> messages,GraphSchema schema,String origin){
        var lines=messages.stream().map(Object::toString).toList();
        assertTrue(lines.stream().anyMatch(line->line.contains(origin+" ontology selected:")));
        String entities=lines.stream().filter(line->line.contains("Ontology entity types:")).findFirst().orElseThrow();
        for(var entity:schema.entities)assertTrue(entities.contains(entity.name()));
        String relationships=lines.stream().filter(line->line.contains("Ontology relationship types:")).findFirst().orElseThrow();
        for(var relation:schema.relationships)assertTrue(relationships.contains(relation.name()+" ("+relation.head()+" -> "+relation.tail()+")"));
    }
    @Test void rejectsUnsafeEndpointsAndAmbiguousAliases(){
        assertThrows(IllegalArgumentException.class,()->new GlinerGraphExtractionPlugin().configure(Map.of("GRAPH_EXTRACT_URL","http://remote.example")));
        assertThrows(IllegalArgumentException.class,()->new GlinerGraphExtractionPlugin().configure(Map.of("GRAPH_EXTRACT_URL","http://localhost","GRAPH_OPTIMIZER","unsupported")));
        assertThrows(IllegalArgumentException.class,()->new AliasRegistry("{\"Person\":{\"Alice\":[\"a\"],\"Bob\":[\"a\"]}}",GraphSchema.defaults()));
    }
}
