package com.core.docintel.pipeline.builder;

import io.micronaut.http.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class McpControllerTest {
    @TempDir Path temp;
    private final List<GraphExecutionManager> executionManagers=new java.util.ArrayList<>();
    @org.junit.jupiter.api.AfterEach void closeExecutionManagers(){executionManagers.forEach(GraphExecutionManager::close);}

    @Test void initializesSessionAndListsToolsUsingStreamableHttpHeaders(){
        McpController controller=controller("token");
        var initialize=request(Map.of("jsonrpc","2.0","id",1,"method","initialize","params",Map.of("protocolVersion",McpController.LEGACY_PROTOCOL)),null,null);
        HttpResponse<?> init=controller.post(initialize);
        assertEquals(HttpStatus.OK,init.getStatus());
        String session=init.getHeaders().get("Mcp-Session-Id");assertNotNull(session);
        assertEquals(McpController.LEGACY_PROTOCOL,init.getHeaders().get("MCP-Protocol-Version"));
        var list=request(Map.of("jsonrpc","2.0","id",2,"method","tools/list","params",Map.of()),session,McpController.LEGACY_PROTOCOL);
        HttpResponse<?> response=controller.post(list);
        assertEquals(HttpStatus.OK,response.getStatus());
        @SuppressWarnings("unchecked") Map<String,Object> body=(Map<String,Object>)response.getBody().orElseThrow();
        @SuppressWarnings("unchecked") Map<String,Object> result=(Map<String,Object>)body.get("result");
        assertTrue(result.containsKey("tools"));
    }

    @Test void requiresBearerTokenAndRejectsUnsupportedOrigins(){
        McpController controller=controller("token");
        var unauthorized=HttpRequest.POST("/mcp",Map.of("jsonrpc","2.0","id",1,"method","ping")).header("Accept","application/json, text/event-stream");
        assertEquals(HttpStatus.UNAUTHORIZED,controller.post(unauthorized).getStatus());
        var badOrigin=request(Map.of("jsonrpc","2.0","id",1,"method","initialize","params",Map.of("protocolVersion",McpController.PROTOCOL)),null,null).header("Origin","https://attacker.example");
        assertEquals(HttpStatus.FORBIDDEN,controller.post(badOrigin).getStatus());
    }

    @Test void supportsModernStatelessDiscoveryAndCallsWithPerRequestMetadata(){
        McpController controller=controller("token");
        Map<String,Object> metadata=modernMetadata();
        var discover=modernRequest(Map.of("jsonrpc","2.0","id",1,"method","server/discover","params",Map.of("_meta",metadata)),"server/discover",null);
        HttpResponse<?> discovery=controller.post(discover);
        assertEquals(HttpStatus.OK,discovery.getStatus());
        @SuppressWarnings("unchecked") Map<String,Object> body=(Map<String,Object>)discovery.getBody().orElseThrow();
        @SuppressWarnings("unchecked") Map<String,Object> result=(Map<String,Object>)body.get("result");
        assertEquals("complete",result.get("resultType"));
        assertEquals(List.of(McpController.PROTOCOL,McpController.LEGACY_PROTOCOL),result.get("supportedVersions"));
        assertFalse(discovery.getHeaders().contains("Mcp-Session-Id"));

        var list=modernRequest(Map.of("jsonrpc","2.0","id",2,"method","tools/list","params",Map.of("_meta",metadata)),"tools/list",null);
        HttpResponse<?> listed=controller.post(list);
        assertEquals(HttpStatus.OK,listed.getStatus());
        @SuppressWarnings("unchecked") Map<String,Object> listBody=(Map<String,Object>)listed.getBody().orElseThrow();
        @SuppressWarnings("unchecked") Map<String,Object> listResult=(Map<String,Object>)listBody.get("result");
        assertEquals("complete",listResult.get("resultType"));
        assertTrue(listResult.containsKey("tools"));
    }

    @Test void rejectsModernHeaderMismatchAndUnsupportedVersion(){
        McpController controller=controller("token");
        Map<String,Object> metadata=modernMetadata();
        var badHeader=modernRequest(Map.of("jsonrpc","2.0","id",1,"method","server/discover","params",Map.of("_meta",metadata)),"tools/list",null);
        assertEquals(HttpStatus.BAD_REQUEST,controller.post(badHeader).getStatus());

        Map<String,Object> unsupported=new java.util.HashMap<>(metadata);
        unsupported.put("io.modelcontextprotocol/protocolVersion","1900-01-01");
        var badVersion=HttpRequest.POST("/mcp",Map.of("jsonrpc","2.0","id",2,"method","server/discover","params",Map.of("_meta",unsupported)))
                .header("Authorization","Bearer token").header("Accept","application/json, text/event-stream")
                .header("Host","127.0.0.1:8080").header("MCP-Protocol-Version","1900-01-01").header("Mcp-Method","server/discover");
        HttpResponse<?> response=controller.post(badVersion);
        assertEquals(HttpStatus.BAD_REQUEST,response.getStatus());
        @SuppressWarnings("unchecked") Map<String,Object> body=(Map<String,Object>)response.getBody().orElseThrow();
        @SuppressWarnings("unchecked") Map<String,Object> error=(Map<String,Object>)body.get("error");
        assertEquals(-32022,error.get("code"));
    }

    @Test void appliesConfiguredControlPlaneRolesToMcpTools(){
        ControlPlaneAuthService auth=new ControlPlaneAuthService(temp.resolve("rbac.db").toString(),true,"admin","correct horse battery staple");
        auth.initialize();
        auth.createUser("reader","correct horse battery staple","VIEWER");
        AuthToken token=auth.login("reader","correct horse battery staple");
        McpController controller=new McpController(application(),auth,"","","");
        Map<String,Object> metadata=modernMetadata();
        Map<String,Object> call=Map.of("jsonrpc","2.0","id",3,"method","tools/call","params",Map.of(
                "name","rag_execute_query","arguments",Map.of("pipelineId","p","version",1,"query","q"),"_meta",metadata));
        var request=HttpRequest.POST("/mcp",call).header("Authorization","Bearer "+token.accessToken())
                .header("Accept","application/json, text/event-stream").header("Host","127.0.0.1:8080")
                .header("MCP-Protocol-Version",McpController.PROTOCOL).header("Mcp-Method","tools/call").header("Mcp-Name","rag_execute_query");
        assertEquals(HttpStatus.FORBIDDEN,controller.post(request).getStatus());
        assertEquals("DENIED_FORBIDDEN",auth.auditEvents(10).getFirst().outcome());

        Map<String,Object> read=Map.of("jsonrpc","2.0","id",4,"method","tools/call","params",Map.of(
                "name","rag_list_pipelines","arguments",Map.of(),"_meta",modernMetadata()));
        var readRequest=HttpRequest.POST("/mcp",read).header("Authorization","Bearer "+token.accessToken())
                .header("Accept","application/json, text/event-stream").header("Host","127.0.0.1:8080")
                .header("MCP-Protocol-Version",McpController.PROTOCOL).header("Mcp-Method","tools/call").header("Mcp-Name","rag_list_pipelines");
        assertEquals(HttpStatus.OK,controller.post(readRequest).getStatus());
    }

    private Map<String,Object> modernMetadata(){return Map.of(
            "io.modelcontextprotocol/protocolVersion",McpController.PROTOCOL,
            "io.modelcontextprotocol/clientInfo",Map.of("name","test-client","version","1.0"),
            "io.modelcontextprotocol/clientCapabilities",Map.of());}
    private MutableHttpRequest<Map<String,Object>> modernRequest(Map<String,Object> body,String method,String name){
        var req=HttpRequest.POST("/mcp",body).header("Authorization","Bearer token")
                .header("Accept","application/json, text/event-stream").header("Host","127.0.0.1:8080")
                .header("MCP-Protocol-Version",McpController.PROTOCOL).header("Mcp-Method",method);
        if(name!=null)req.header("Mcp-Name",name);
        return req;
    }

    private MutableHttpRequest<Map<String,Object>> request(Map<String,Object> body,String session,String protocol){MutableHttpRequest<Map<String,Object>> req=HttpRequest.POST("/mcp",body).header("Authorization","Bearer token").header("Accept","application/json, text/event-stream").header("Host","127.0.0.1:8080");if(session!=null)req.header("Mcp-Session-Id",session);if(protocol!=null)req.header("MCP-Protocol-Version",protocol);return req;}
    private McpController controller(String token){return new McpController(application(),token,"","");}
    private McpApplicationService application(){SqlitePipelineStore store=new SqlitePipelineStore(temp.resolve("studio.db").toString());store.initialize();SqliteExecutionStore executionStore=new SqliteExecutionStore(temp.resolve("studio.db").toString());executionStore.initialize();PluginCatalog catalog=new PluginCatalog(temp.resolve("plugins").toString());GraphPipelineValidator validator=new GraphPipelineValidator();PipelineControlService control=new PipelineControlService(store,validator,catalog);GraphExecutionManager executionManager=new GraphExecutionManager(control,catalog,validator,executionStore,temp.resolve("ingestion").toString());executionManagers.add(executionManager);return new McpApplicationService(control,catalog,validator,executionManager);}
}
