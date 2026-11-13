package com.core.docintel.response;
import com.sun.net.httpserver.*;
import java.net.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/** Explicit, application-owned singleton. Per-query plugin close does not invalidate browser URLs. */
public final class EmbeddedResponseServer implements AutoCloseable {
    private static EmbeddedResponseServer shared;private static boolean hook;
    private static final String ID="[a-f0-9-]{36}";
    private final HttpServer server;private final ResponseConfig config;private final ResponseStore store;
    private final ExecutorService workers;private final ScheduledExecutorService cleanup;private final CountDownLatch closed=new CountDownLatch(1);
    private final Map<String,byte[]> assets=new HashMap<>();private final AtomicBoolean browserOpened=new AtomicBoolean();
    private static final Logger LOG=Logger.getLogger(EmbeddedResponseServer.class.getName());
    public static synchronized EmbeddedResponseServer acquire(ResponseConfig config){
        if(shared!=null&&shared.closed.getCount()==0)shared=null;
        if(shared!=null){if(shared.config.port()!=config.port()||shared.config.maxResponses()!=config.maxResponses()||shared.config.ageMinutes()!=config.ageMinutes()||shared.config.maxStoreBytes()!=config.maxStoreBytes()||shared.config.maxResponseBytes()!=config.maxResponseBytes())throw new IllegalArgumentException("Universal Response server already runs with different port or storage settings. Use consistent application-wide settings.");return shared;}
        shared=new EmbeddedResponseServer(config);if(!hook){Runtime.getRuntime().addShutdownHook(new Thread(EmbeddedResponseServer::shutdown,"universal-response-shutdown"));hook=true;}return shared;
    }
    public static synchronized void shutdown(){if(shared!=null){shared.close();shared=null;}}
    private EmbeddedResponseServer(ResponseConfig config){
        this.config=config;store=new ResponseStore(config);
        for(String name:List.of("index.html","app.css","app.js","renderers.js"))try(var input=EmbeddedResponseServer.class.getResourceAsStream("/response-ui/"+name)){if(input==null)throw new IllegalStateException("Bundled response UI resource is missing: "+name);assets.put("/"+name,input.readAllBytes());}catch(IOException e){throw new IllegalStateException("Cannot load bundled response UI.");}
        try{server=HttpServer.create(new InetSocketAddress("127.0.0.1",config.port()),32);}catch(IOException e){throw new IllegalStateException("Universal Response cannot bind 127.0.0.1:"+config.port()+". The port may be occupied; choose UR_PORT or disable UR_UI_ENABLED.");}
        workers=new ThreadPoolExecutor(2,4,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(64),Thread.ofPlatform().daemon(true).name("response-http-",0).factory(),new ThreadPoolExecutor.AbortPolicy());
        cleanup=Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon(true).name("response-cleanup").factory());
        server.setExecutor(workers);server.createContext("/",this::handle);server.start();cleanup.scheduleAtFixedRate(store::cleanup,1,1,TimeUnit.MINUTES);
        Thread.ofPlatform().daemon(false).name("response-viewer-lifetime").start(()->{try{closed.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}});
        LOG.info("Universal Response embedded server started on "+baseUrl());
    }
    public String baseUrl(){return "http://127.0.0.1:"+server.getAddress().getPort();}
    public String viewerUrl(String id){return baseUrl()+"/responses/"+id;}
    public ResponseStore store(){return store;}
    boolean openBrowser(boolean every){return every||browserOpened.compareAndSet(false,true);}
    private void handle(HttpExchange exchange){
        try{
            var headers=exchange.getResponseHeaders();headers.set("X-Content-Type-Options","nosniff");headers.set("Referrer-Policy","no-referrer");headers.set("X-Frame-Options","DENY");headers.set("Cross-Origin-Resource-Policy","same-origin");headers.set("Cache-Control","no-store");
            headers.set("Content-Security-Policy","default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'; img-src 'self' https:; media-src https:; frame-src 'self' blob:; object-src 'none'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'");
            String host=exchange.getRequestHeaders().getFirst("Host"),origin=exchange.getRequestHeaders().getFirst("Origin"),site=exchange.getRequestHeaders().getFirst("Sec-Fetch-Site");
            if(!Objects.equals(host,"127.0.0.1:"+server.getAddress().getPort())||origin!=null&&!origin.equals(baseUrl())||site!=null&&!Set.of("same-origin","none").contains(site)){error(exchange,403,"Forbidden origin or host");return;}
            if(!Set.of("GET","HEAD").contains(exchange.getRequestMethod())){headers.set("Allow","GET, HEAD");error(exchange,405,"Method not allowed");return;}
            if(exchange.getRequestHeaders().size()>40||exchange.getRequestHeaders().values().stream().flatMap(Collection::stream).anyMatch(value->value.length()>8192)||exchange.getRequestHeaders().containsKey("Transfer-Encoding")||!Objects.toString(exchange.getRequestHeaders().getFirst("Content-Length"),"0").equals("0")){error(exchange,400,"Unexpected or oversized request headers/body");return;}
            String path=exchange.getRequestURI().getRawPath();if(path.length()>2048||path.contains("%")||path.contains("..")||path.contains("\\")||exchange.getRequestURI().getRawQuery()!=null){error(exchange,400,"Invalid request path");return;}
            if(path.equals("/health")){send(exchange,200,"application/json",ResponseJson.bytes(Map.of("status","ok","responses",store.size())));return;}
            if(path.equals("/api/responses")){send(exchange,200,"application/json",ResponseJson.bytes(store.list()));return;}
            if(path.equals("/api/responses/latest")||path.matches("/api/responses/"+ID)){
                var entry=path.endsWith("/latest")?store.latest():store.get(path.substring("/api/responses/".length()));if(entry==null){error(exchange,404,"Response expired or not found");return;}send(exchange,200,"application/json",entry.json());return;
            }
            if(path.matches("/api/artifacts/"+ID)){
                var file=store.artifact(path.substring("/api/artifacts/".length()));if(file==null){error(exchange,404,"Artifact expired or not found");return;}
                headers.set("Content-Disposition","attachment; filename=\""+file.name()+"\"");headers.set("Content-Security-Policy","sandbox; default-src 'none'; base-uri 'none'; form-action 'none'");send(exchange,200,file.mime(),file.bytes());return;
            }
            if(path.equals("/")||path.matches("/responses/"+ID))path="/index.html";
            byte[] resource=assets.get(path);if(resource==null){error(exchange,404,"Not found");return;}
            send(exchange,200,path.endsWith(".js")?"text/javascript":path.endsWith(".css")?"text/css":"text/html",resource);
        }catch(Exception ignored){try{error(exchange,500,"Response server could not process the request");}catch(IOException ignoredAgain){}}finally{exchange.close();}
    }
    private static void error(HttpExchange exchange,int status,String text)throws IOException{send(exchange,status,"application/json",ResponseJson.bytes(Map.of("error",text)));}
    private static void send(HttpExchange exchange,int status,String mime,byte[] bytes)throws IOException{
        exchange.getResponseHeaders().set("Content-Type",mime+(mime.startsWith("text/")||mime.equals("application/json")?"; charset=utf-8":""));
        if(exchange.getRequestMethod().equals("HEAD")){exchange.getResponseHeaders().set("Content-Length",""+bytes.length);exchange.sendResponseHeaders(status,-1);return;}
        exchange.sendResponseHeaders(status,bytes.length);exchange.getResponseBody().write(bytes);
    }
    public void close(){if(closed.getCount()==0)return;server.stop(0);cleanup.shutdownNow();workers.shutdownNow();store.clear();closed.countDown();}
}
