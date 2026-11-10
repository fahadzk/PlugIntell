package com.core.docintel.pipeline.builder;
import com.core.docintel.pipeline.events.*;
import jakarta.inject.Singleton;
import io.micronaut.context.annotation.Value;
import java.io.*;
import java.nio.file.*;
import java.util.*;
@Singleton
public final class TemplateCatalog {
 private final Path root;
 public TemplateCatalog(@Value("${pipeline.templates.root:./data/templates}")String root){this.root=Path.of(root).toAbsolutePath().normalize();}
 private Map<String,byte[]> bundled(){
  Map<String,byte[]> bundles=new LinkedHashMap<>();
  for(String name:List.of("aurora","midnight"))try(var stream=getClass().getResourceAsStream("/ui-templates/"+name+".zip")){
   if(stream==null)throw new IllegalStateException("Bundled UI template is missing: "+name);
   byte[] bytes=stream.readAllBytes();bundles.put(TemplateBundle.read(bytes,false).id(),bytes);
  }catch(IOException e){throw new IllegalStateException("Could not read bundled UI templates.",e);}
  return bundles;
 }
 public List<Map<String,Object>> list(){
  Map<String,Map<String,Object>> all=new LinkedHashMap<>();
  for(var template:List.of(builtin("standard","Live workflow"),builtin("water","Water pipeline")))all.put((String)template.get("id"),template);
  bundled().forEach((id,bytes)->all.put(id,TemplateBundle.read(bytes,false).descriptor()));
  try{if(Files.isDirectory(root))try(var paths=Files.list(root)){for(Path path:paths.filter(p->p.getFileName().toString().endsWith(".zip")).sorted().toList()){
   var template=TemplateBundle.read(Files.readAllBytes(path),true);all.putIfAbsent(template.id(),template.descriptor());
  }}}catch(IOException e){throw new IllegalStateException("Could not read templates.",e);}
  return List.copyOf(all.values());
 }
 private static Map<String,Object> builtin(String id,String name){return Map.of("id",id,"name",name,"version","1","description","Built-in accessible workflow monitor","executable",false,"settingsSchema",Map.of("type","object","properties",Map.of("accent",Map.of("type","string"),"title",Map.of("type","string"))));}
 public synchronized Map<String,Object> importBundle(byte[] bytes,boolean trusted){
  TemplateBundle bundle=TemplateBundle.read(bytes,trusted);
  try{preserve(bundle.id(),bytes);return bundle.descriptor();}
  catch(IOException e){throw new IllegalStateException("Could not store template.",e);}
 }
 private synchronized void preserve(String id,byte[] bytes)throws IOException{
  Files.createDirectories(root);Path target=root.resolve(id+".zip");if(Files.exists(target))return;
  Path staged=Files.createTempFile(root,".template-",".part");
  try{Files.write(staged,bytes);try{Files.move(staged,target,StandardCopyOption.ATOMIC_MOVE);}catch(AtomicMoveNotSupportedException unsupported){Files.move(staged,target);}catch(FileAlreadyExistsException alreadyPinned){}}
  finally{Files.deleteIfExists(staged);}
 }
 public byte[] archive(String id){
  if(!id.matches("[A-Za-z0-9_-]{1,96}"))throw new IllegalArgumentException("Invalid template ID.");
  byte[] bytes=bundled().get(id);if(bytes!=null)return bytes;
  try{byte[] stored=Files.readAllBytes(root.resolve(id+".zip"));if(!TemplateBundle.read(stored,true).id().equals(id))throw new IllegalArgumentException("Template contents do not match the immutable pin.");return stored;}catch(IOException e){throw new NoSuchElementException("Template not found.");}
 }
 public TemplateBundle get(String id){return TemplateBundle.read(archive(id),true);}
 public void validatePin(Map<String,Object> capabilities){
  Map<String,Object> template=CapabilitySettings.map(capabilities==null?null:capabilities.get("template"));String id=CapabilitySettings.text(template,"id","standard");
  Object schema=Set.of("standard","water").contains(id)?builtin(id,id).get("settingsSchema"):get(id).manifest().get("settingsSchema");
  TemplateSettings.validate(CapabilitySettings.map(template.get("settings")),schema);
  if(!Set.of("standard","water").contains(id)){byte[] archive=bundled().get(id);if(archive!=null)try{preserve(id,archive);}catch(IOException e){throw new IllegalStateException("Could not preserve the published template version.",e);}}
 }
}
