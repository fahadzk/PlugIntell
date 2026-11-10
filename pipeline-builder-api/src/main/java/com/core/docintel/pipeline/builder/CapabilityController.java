package com.core.docintel.pipeline.builder;
import io.micronaut.http.*;
import io.micronaut.http.annotation.*;
import io.micronaut.http.multipart.CompletedFileUpload;
import java.util.*;
@Controller
public final class CapabilityController{
 private final AdapterService adapters;private final TemplateCatalog templates;
 public CapabilityController(AdapterService adapters,TemplateCatalog templates){this.adapters=adapters;this.templates=templates;}
 @Get("/api/v1/adapters") public List<Map<String,Object>> adapters(){return adapters.catalog();}
 @Post("/api/v1/adapters/{type}/test") public Map<String,Object> test(String type,@Body Map<String,Object> request){if("ui".equals(type)&&request.containsKey("template"))templates.validatePin(Map.of("template",request.get("template")));return adapters.test(type,CapabilitySettings.map(request.get("config")));}
 @Get("/api/v1/templates") public List<Map<String,Object>> templates(){return templates.list();}
 @Get(uri="/api/v1/templates/{id}/bundle",produces="application/zip") public HttpResponse<byte[]> bundle(String id){return HttpResponse.ok(templates.archive(id)).contentType("application/zip").header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename="+id+".zip");}
 @Post(uri="/api/v1/templates/import",consumes=MediaType.MULTIPART_FORM_DATA) public Map<String,Object> importTemplate(@Part("file") CompletedFileUpload file,@QueryValue(defaultValue="false") boolean trustedExecutable)throws java.io.IOException{if(file.getSize()>2_000_000)throw new IllegalArgumentException("Template archive exceeds 2 MB.");return templates.importBundle(file.getBytes(),trustedExecutable);}
 @Get("/ui/templates/{id}/{+path}") public HttpResponse<?> resource(String id,String path){byte[] data=templates.get(id).files().get(path);if(data==null)return HttpResponse.notFound();String type=path.endsWith(".html")?"text/html":path.endsWith(".css")?"text/css":path.endsWith(".js")?"text/javascript":path.endsWith(".svg")?"image/svg+xml":path.endsWith(".png")?"image/png":path.endsWith(".woff2")?"font/woff2":"application/octet-stream";return HttpResponse.ok(data).contentType(type).header("X-Content-Type-Options","nosniff").header("Content-Security-Policy","sandbox allow-scripts; default-src 'none'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; font-src 'self'; connect-src 'none'; form-action 'none'; base-uri 'none'");}
 @io.micronaut.http.annotation.Error(exception=IllegalArgumentException.class) public HttpResponse<?> invalid(IllegalArgumentException e){return HttpResponse.badRequest(Map.of("error",e.getMessage()));}
 @io.micronaut.http.annotation.Error(exception=NoSuchElementException.class) public HttpResponse<?> missing(NoSuchElementException e){return HttpResponse.notFound(Map.of("error",e.getMessage()));}
}

