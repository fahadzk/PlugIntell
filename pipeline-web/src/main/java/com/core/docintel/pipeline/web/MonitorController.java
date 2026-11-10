package com.core.docintel.pipeline.web;
import com.core.docintel.pipeline.events.*;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.*;
import io.micronaut.http.annotation.*;
import io.micronaut.http.annotation.Error;
import io.micronaut.http.sse.Event;
import org.reactivestreams.Publisher;
import java.util.*;
@Controller("/api/v1/executions")
@Requires(beans=ApplicationServices.class)
public final class MonitorController{
 private final ApplicationServices services;private final WebConfig config;
 public MonitorController(ApplicationServices services,WebConfig config){this.services=services;this.config=config;}
 @Get public List<Map<String,Object>> history(){return services.history();}
 @Get("/{id}/monitor") public Map<String,Object> snapshot(String id){return services.get(id).snapshot();}
 @Get("/{id}/result") public HttpResponse<Map<String,Object>> result(String id){return HttpResponse.ok(services.get(id).result()).header(HttpHeaders.CACHE_CONTROL,"no-store");}
 @Get("/{id}/events{?afterSequence}") public List<Map<String,Object>> events(String id,@QueryValue(defaultValue="0") long afterSequence){return services.get(id).replay(afterSequence);}
 @Get(uri="/{id}/stream{?afterSequence}",produces=MediaType.TEXT_EVENT_STREAM)
 public Publisher<Event<Map<String,Object>>> stream(String id,@QueryValue(defaultValue="0") long afterSequence,@Header("Last-Event-ID") Optional<String> lastId){var s=services.get(id);long cursor=lastId.filter(x->!x.isBlank()).map(Long::parseLong).orElse(afterSequence);return SseStreams.stream(cursor,s::snapshot,s::replay,s.state::gap);}
 @Post public HttpResponse<?> run(@Body Map<String,String> request){String mode=request.get("mode"),input=request.getOrDefault("input",request.getOrDefault("query",request.getOrDefault("filePath","")));if(input.isBlank())throw new IllegalArgumentException("Input is required.");if("ingestion".equals(mode))input=config.ingestionFile(input).toString();return HttpResponse.status(HttpStatus.ACCEPTED).body(Map.of("executionId",services.start(mode,input)));}
 @Post("/{id}/cancel") public HttpResponse<?> cancel(String id){return services.cancel(id)?HttpResponse.accepted():HttpResponse.notFound();}
 @Error(exception=IllegalArgumentException.class) public HttpResponse<?> invalid(IllegalArgumentException e){return HttpResponse.badRequest(Map.of("error",e.getMessage()));}
 @Error(exception=NoSuchElementException.class) public HttpResponse<?> missing(NoSuchElementException e){return HttpResponse.notFound(Map.of("error",e.getMessage()));}
}

