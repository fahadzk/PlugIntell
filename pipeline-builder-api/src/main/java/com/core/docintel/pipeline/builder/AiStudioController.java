package com.core.docintel.pipeline.builder;
import com.core.docintel.pipeline.builder.helpers.LayaPluginSelector;
import io.micronaut.context.annotation.Value;
import io.micronaut.http.*;
import io.micronaut.http.annotation.*;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.serde.annotation.Serdeable;
import java.util.Map;

@Controller("/api/v1/aistudio")
@ExecuteOn(TaskExecutors.BLOCKING)
public final class AiStudioController {
    @Serdeable public record SuggestRequest(String prompt) {}
    private final PluginCatalog catalog;
    private final LayaPluginSelector selector;
    public AiStudioController(PluginCatalog catalog,
        @Value("${laya.base-url}") String baseUrl,
        @Value("${laya.timeout-seconds}") int timeout,
        @Value("${laya.api-key:}") String key) {
        this.catalog=catalog;selector=new LayaPluginSelector(baseUrl,timeout,key);
    }
    @Post("/recommend") public HttpResponse<?> recommend(@Body SuggestRequest request) {
        try {
            var snapshot=catalog.all().stream().map(p->new LayaPluginSelector.Candidate(p.id(),p.name(),p.version(),p.description(),p.capabilities(),p.workflows().stream().map(Enum::name).toList(),p.inputType(),p.outputType())).toList();
            return HttpResponse.ok(selector.recommend(request==null?null:request.prompt(),snapshot));
        } catch(IllegalArgumentException e){return HttpResponse.badRequest(Map.of("error",e.getMessage()));}
        catch(IllegalStateException e){return HttpResponse.status(HttpStatus.BAD_GATEWAY).body(Map.of("error",e.getMessage()));}
    }
}
