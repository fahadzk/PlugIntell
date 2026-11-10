package com.core.docintel.pipeline.web;
import com.core.docintel.pipeline.events.ApplicationServices;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.*;
import io.micronaut.http.annotation.*;
import java.util.*;
@Controller("/ui/templates")
@Requires(beans=ApplicationServices.class)
public final class TemplateResourceController{
 @Get("/{id}/{+path}") public HttpResponse<?> resource(String id,String path)throws java.io.IOException{
  if(!id.matches("[A-Za-z0-9_-]{1,96}")||!path.matches("[A-Za-z0-9_./-]{1,180}")||Arrays.asList(path.split("/")).contains(".."))return HttpResponse.notFound();
  try(var stream=getClass().getResourceAsStream("/public/templates/"+id+"/"+path)){if(stream==null)return HttpResponse.notFound();String type=path.endsWith(".html")?"text/html":path.endsWith(".css")?"text/css":path.endsWith(".js")?"text/javascript":path.endsWith(".png")?"image/png":path.endsWith(".svg")?"image/svg+xml":"application/octet-stream";return HttpResponse.ok(stream.readAllBytes()).contentType(type).header("X-Content-Type-Options","nosniff").header("Content-Security-Policy","sandbox allow-scripts; default-src 'none'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; font-src 'self'; connect-src 'none'; form-action 'none'; base-uri 'none'");}
 }
}

