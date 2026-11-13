package com.core.docintel.response;
import com.core.docintel.pipeline.api.*;
import java.awt.Desktop;
import java.net.URI;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import static com.core.docintel.response.ResponseModel.*;

public final class UniversalResponsePlugin extends AbstractPlugin implements ConfigurablePlugin,ApplicationScopedPlugin {
    private ResponseConfig config=ResponseConfig.from(Map.of());private volatile Response lastResponse;private volatile String viewerUrl;
    private final RendererRegistry registry=RendererRegistry.standard();private final ArtifactManager artifacts=ArtifactManager.standard();
    public UniversalResponsePlugin(){super();}
    public UniversalResponsePlugin(PluginMessages messages){super(messages);}
    public PluginManifest manifest(){return new PluginManifest("universal-response","Universal Response Renderer","1.0.0","1.0",getClass().getName(),"AnswerResult|RetrievalResult","AnswerResult|RetrievalResult");}
    public void configure(Map<String,Object> configuration){config=ResponseConfig.from(configuration);info("UniversalResponsePlugin initialized.");}
    public Object execute(Object input){
        long start=System.nanoTime();emit("response.received",Map.of("inputType",input==null?"null":input.getClass().getSimpleName()));
        Response response=new ResponseDetector(registry).normalize(input,config);emit("response.normalized",Map.of("responseId",response.id(),"types",response.blocks().stream().map(Block::type).toList()));
        if(config.ui()){
            var server=EmbeddedResponseServer.acquire(config);List<ArtifactManager.File> files=List.of();
            if(config.artifacts())try{files=artifacts.create(response);}catch(RuntimeException failedExport){warning("Artifact creation failed; independent response blocks remain available.");}
            try{response=server.store().put(response,files);}catch(IllegalArgumentException tooLarge){
                var fallback=new Response("1.0",response.id(),Instant.now().toString(),response.query().substring(0,Math.min(40,response.query().length())),"warning",List.of(block("text","Response exceeded the configured viewer memory limit. The original pipeline result is preserved.")),List.of(),Map.of("theme",config.theme()),List.of("Content and exports were omitted to keep bounded storage."),List.of());response=server.store().put(fallback,List.of());
            }
            viewerUrl=server.viewerUrl(response.id());info("Viewer ready; it remains available until application exit or retention expiry.");
            var uuid=UUID.fromString(response.id());byte[] identifier=java.nio.ByteBuffer.allocate(16).putLong(uuid.getMostSignificantBits()).putLong(uuid.getLeastSignificantBits()).array();
            emit("response.viewer.available",Map.of("viewerPort",URI.create(viewerUrl).getPort(),"viewerIdBytes",java.util.stream.IntStream.range(0,16).mapToObj(i->(int)identifier[i]).toList()));
            emit("response.stored",Map.of("responseId",response.id(),"artifactCount",response.artifacts().size()));
            if(config.browser()&&server.openBrowser(config.everyResponse())){
                if(java.awt.GraphicsEnvironment.isHeadless())warning("Browser opening is unavailable in this headless environment; open the viewer URL manually.");
                else{String url=viewerUrl,responseId=response.id();CompletableFuture.runAsync(()->{var log=java.util.logging.Logger.getLogger(UniversalResponsePlugin.class.getName());try{if(Desktop.isDesktopSupported()&&Desktop.getDesktop().isSupported(Desktop.Action.BROWSE))Desktop.getDesktop().browse(URI.create(url));else log.warning("Browser opening is unavailable for response "+responseId+"; open the viewer URL manually.");}catch(Exception e){log.warning("Could not open the browser for response "+responseId+"; open the viewer URL manually.");}});}
            }
        }
        lastResponse=response;emit("response.rendering.completed",Map.of("responseId",response.id(),"durationMs",(System.nanoTime()-start)/1_000_000,"uiEnabled",config.ui()));return input;
    }
    public Response lastResponse(){return lastResponse;}
    public String viewerUrl(){return viewerUrl;}
    /** The application owns the shared server. Query cleanup intentionally preserves stored views. */
    public void close(){}
    public void closeApplication(){EmbeddedResponseServer.shutdown();}
    public static void shutdownServer(){EmbeddedResponseServer.shutdown();}
}
