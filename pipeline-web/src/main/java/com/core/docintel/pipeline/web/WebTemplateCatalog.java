package com.core.docintel.pipeline.web;
import com.core.docintel.pipeline.events.ApplicationServices;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.annotation.*;
import org.yaml.snakeyaml.*;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import java.util.*;
@Controller("/api/v1/templates")
@Requires(beans=ApplicationServices.class)
public final class WebTemplateCatalog{
 private final ApplicationServices services;
 public WebTemplateCatalog(ApplicationServices services){this.services=services;}
 @Get public List<Map<String,Object>> list()throws java.io.IOException{
  List<Map<String,Object>> result=new ArrayList<>(List.of(Map.of("id","standard","version","1","name","Live workflow"),Map.of("id","water","version","1","name","Water pipeline")));
  Map<?,?> template=(Map<?,?>)services.application().get("template");String id=Objects.toString(template.get("id"),"standard");
  if(!Set.of("standard","water").contains(id)&&id.matches("[A-Za-z0-9_-]{1,96}"))try(var stream=getClass().getResourceAsStream("/public/templates/"+id+"/manifest.json")){if(stream!=null){Map<?,?> manifest=new Yaml(new SafeConstructor(new LoaderOptions())).load(stream);Map<String,Object> entry=new LinkedHashMap<>();entry.put("id",id);entry.put("version","1");entry.put("name",Objects.toString(manifest.get("name"),id));entry.put("description",Objects.toString(manifest.get("description"),""));entry.put("executable",Boolean.TRUE.equals(manifest.get("executable")));entry.put("rendererUrl","/ui/templates/"+id+"/index.html");if(manifest.get("settingsSchema")!=null)entry.put("settingsSchema",manifest.get("settingsSchema"));result.add(entry);}}
  return result;
 }
}

