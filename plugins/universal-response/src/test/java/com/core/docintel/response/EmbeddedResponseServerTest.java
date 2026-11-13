package com.core.docintel.response;
import com.core.docintel.pipeline.api.*;
import org.junit.jupiter.api.*;
import java.net.*;
import java.net.http.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(25)
class EmbeddedResponseServerTest {
    final HttpClient client=HttpClient.newHttpClient();
    @AfterEach void shutdown(){UniversalResponsePlugin.shutdownServer();}
    UniversalResponsePlugin plugin(Map<String,Object> overrides){var config=new HashMap<String,Object>(Map.of("UR_PORT",0,"UR_AUTO_OPEN_BROWSER",false));config.putAll(overrides);var plugin=new UniversalResponsePlugin();plugin.configure(config);return plugin;}
    static AnswerResult answer(String value){return new AnswerResult("Question",value,false,List.of());}
    HttpResponse<String> get(String url)throws Exception{return client.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),HttpResponse.BodyHandlers.ofString());}
    @Test void servesBundledUiResponsesAndRealCsvDownloadAfterPluginClose()throws Exception{
        var plugin=plugin(Map.of());var input=answer("[{\"month\":\"Jan\",\"revenue\":120}]");assertSame(input,plugin.execute(input));String base=URI.create(plugin.viewerUrl()).resolve("/").toString();plugin.close();
        var page=get(plugin.viewerUrl());assertEquals(200,page.statusCode());assertTrue(page.body().contains("Response Studio"));assertTrue(page.headers().firstValue("Content-Security-Policy").orElseThrow().contains("script-src 'self'"));assertFalse(page.body().contains("cdn."));
        for(String path:List.of("app.css","app.js","renderers.js")){var resource=get(base+path);assertEquals(200,resource.statusCode());assertTrue(resource.body().length()>500);}
        var latest=get(base+"api/responses/latest");assertEquals(200,latest.statusCode());var data=ResponseJson.object(ResponseJson.parse(latest.body()));assertEquals(plugin.lastResponse().id(),data.get("id"));
        var csv=plugin.lastResponse().artifacts().stream().filter(a->a.mime().equals("text/csv")).findFirst().orElseThrow();var download=get(base+"api/artifacts/"+csv.id());assertEquals(200,download.statusCode());assertTrue(download.body().contains("Jan"));assertTrue(download.headers().firstValue("Content-Disposition").orElseThrow().contains("attachment"));
        var head=client.send(HttpRequest.newBuilder(URI.create(base+"app.js")).method("HEAD",HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.ofString());assertEquals(200,head.statusCode());assertTrue(head.body().isEmpty());
    }
    @Test void strictHostOriginMethodsAndPathsPreventCrossSiteAndFilesystemAccess()throws Exception{
        var plugin=plugin(Map.of());plugin.execute(answer("Hello"));String base=URI.create(plugin.viewerUrl()).resolve("/").toString();
        for(String path:List.of("api/artifacts/../../secret","api/artifacts/%2e%2e%2fsecret","api/artifacts/uuid?path=C:/secret"))assertEquals(400,get(base+path).statusCode());
        assertEquals(404,get(base+"api/artifacts/not-an-id").statusCode());
        var forbidden=client.send(HttpRequest.newBuilder(URI.create(base+"api/responses/latest")).header("Origin","https://evil.example").GET().build(),HttpResponse.BodyHandlers.ofString());assertEquals(403,forbidden.statusCode());
        var post=client.send(HttpRequest.newBuilder(URI.create(base+"health")).POST(HttpRequest.BodyPublishers.ofString("x")).build(),HttpResponse.BodyHandlers.ofString());assertEquals(405,post.statusCode());
        try(var socket=new Socket("127.0.0.1",URI.create(base).getPort())){socket.setSoTimeout(5000);socket.getOutputStream().write("GET /health HTTP/1.1\r\nHost: evil.example\r\nConnection: close\r\n\r\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII));assertTrue(new BufferedReader(new InputStreamReader(socket.getInputStream())).readLine().contains("403"));}
        assertTrue(get(base+"health").body().contains("ok"));
    }
    @Test void concurrentQueriesShareOneServerAndBoundStorage()throws Exception{
        List<Future<String>> urls=new ArrayList<>();try(var pool=Executors.newFixedThreadPool(8)){for(int i=0;i<24;i++){int n=i;urls.add(pool.submit(()->{var p=plugin(Map.of("UR_MAX_RESPONSES",10));p.execute(answer("Response "+n));p.close();return p.viewerUrl();}));}}
        Set<String> hosts=new HashSet<>();for(var url:urls)hosts.add(URI.create(url.get()).getAuthority());assertEquals(1,hosts.size());String base="http://"+hosts.iterator().next()+"/";var health=ResponseJson.object(ResponseJson.parse(get(base+"health").body()));assertEquals(10,health.get("responses"));assertEquals(200,get(base+"api/responses/latest").statusCode());
        var mismatched=plugin(Map.of("UR_PORT",8764));assertThrows(IllegalArgumentException.class,()->mismatched.execute(answer("x")));
    }
    @Test void portConflictIsClearAndDisabledUiDoesNotBind()throws Exception{
        try(var socket=new ServerSocket(0,16,InetAddress.getByName("127.0.0.1"))){var busy=plugin(Map.of("UR_PORT",socket.getLocalPort()));var failure=assertThrows(IllegalStateException.class,()->busy.execute(answer("x")));assertTrue(failure.getMessage().contains("UR_PORT"));
            var disabled=plugin(Map.of("UR_UI_ENABLED",false,"UR_PORT",socket.getLocalPort()));var value=answer("Headless normalization");assertSame(value,disabled.execute(value));assertNotNull(disabled.lastResponse());assertNull(disabled.viewerUrl());}
    }
}
