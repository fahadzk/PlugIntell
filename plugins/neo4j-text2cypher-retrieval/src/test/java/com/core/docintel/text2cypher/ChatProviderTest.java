package com.core.docintel.text2cypher;
import com.core.docintel.pipeline.api.*;
import com.core.docintel.neo4j.GraphPolicy;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.util.*;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

class ChatProviderTest {
    @Test void allThreeProvidersGenerateExecutablePlansThroughTheirRealHttpAdapters()throws Exception{
        var server=HttpServer.create(new InetSocketAddress("localhost",0),0);var paths=new ArrayList<String>();var payloads=new ArrayList<String>();
        String plan="{\"cypher\":\"MATCH(s:Station) RETURN s.name ORDER BY s.name LIMIT 100\",\"parameters\":[]}";
        server.createContext("/",e->{
            String path=e.getRequestURI().getRawPath();paths.add(path);payloads.add(new String(e.getRequestBody().readAllBytes(),UTF_8));Object response;
            if(path.equals("/api/show"))response=Map.of("capabilities",List.of("completion"),"model_info",Map.of("granite.context_length",32768));
            else if(path.equals("/api/chat"))response=Map.of("message",Map.of("content",plan));
            else if(path.equals("/v1/chat/completions"))response=Map.of("choices",List.of(Map.of("finish_reason","stop","message",Map.of("content",plan))));
            else if(path.endsWith(":generateContent"))response=Map.of("candidates",List.of(Map.of("finishReason","STOP","content",Map.of("parts",List.of(Map.of("text",plan))))));
            else if(path.startsWith("/v1/models/"))response=Map.of("id","ibm-granite/granite-3.3-2b-instruct");
            else response=Map.of("supportedGenerationMethods",List.of("generateContent"),"inputTokenLimit",32768,"outputTokenLimit",8192);
            byte[] bytes=GraphPolicy.encode(response).getBytes(UTF_8);e.sendResponseHeaders(200,bytes.length);try(var out=e.getResponseBody()){out.write(bytes);}
        });server.start();
        try{
            String base="http://localhost:"+server.getAddress().getPort();
            for(String provider:List.of("OLLAMA","OPENAI","GEMINI")){
                var config=new HashMap<>(Text2CypherPluginTest.config());config.put("LLM_PROVIDER",provider);config.put(provider+"_BASE_URL",base);config.put(provider+"_TIMEOUT_SECONDS",2);
                config.put(provider.equals("OLLAMA")?"OLLAMA_CHAT_MODEL":provider+"_MODEL",provider.equals("OPENAI")?"ibm-granite/granite-3.3-2b-instruct":"fixture");if(!provider.equals("OLLAMA"))config.put(provider+"_API_KEY","private-test-key");
                var graph=new Text2CypherPluginTest.Graph();var messages=new ArrayList<PluginMessage>();
                var plugin=new Neo4JText2CypherRetrievalPlugin(messages::add,graph,null);plugin.configure(config);
                try{plugin.preflight();var result=(RetrievalResult)plugin.execute(new SearchRequest("Stations",false));assertEquals(31,result.chunks().size());assertEquals(1,graph.queries);assertEquals(provider,result.graph().get("provider"));assertFalse(messages.toString().contains("private-test-key"));}finally{plugin.close();}
            }
            assertTrue(paths.contains("/api/chat"));assertTrue(paths.contains("/v1/chat/completions"));assertTrue(paths.contains("/v1beta/models/fixture:generateContent"));
            assertTrue(paths.contains("/v1/models/ibm-granite%2Fgranite-3.3-2b-instruct"));
            assertTrue(payloads.stream().anyMatch(p->p.contains("response_format")&&p.contains("parameters")));assertTrue(payloads.stream().anyMatch(p->p.contains("responseJsonSchema")&&p.contains("parameters")));
            assertFalse(payloads.toString().contains("private-test-key"));
        }finally{server.stop(0);}
    }
}
