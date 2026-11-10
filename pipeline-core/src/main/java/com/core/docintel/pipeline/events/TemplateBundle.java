package com.core.docintel.pipeline.events;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.zip.*;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.constructor.SafeConstructor;
/** Immutable version-1 template bundle with bounded archive validation. */
public record TemplateBundle(String id,Map<String,Object> manifest,Map<String,byte[]> files){
 public static TemplateBundle read(byte[] archive,boolean trustedExecutable){
  if(archive.length>2_000_000)throw new IllegalArgumentException("Template archive exceeds 2 MB.");
  Map<String,byte[]> files=new LinkedHashMap<>();int total=0;
  try(ZipInputStream zip=new ZipInputStream(new ByteArrayInputStream(archive))){ZipEntry entry;while((entry=zip.getNextEntry())!=null){
   String name=entry.getName();if(entry.isDirectory())continue;
   if(!name.matches("[A-Za-z0-9_./-]{1,180}")||name.startsWith("/")||Arrays.asList(name.split("/")).contains("..")||Arrays.asList(name.split("/")).contains(".")||name.contains("//"))throw new IllegalArgumentException("Unsafe template archive path.");
   if(files.size()>=100||files.containsKey(name))throw new IllegalArgumentException("Too many or duplicate template resources.");
   if(!name.equals("manifest.json")&&!name.matches("(index\\.html|styles\\.css|renderer\\.js|assets/[A-Za-z0-9_./-]+\\.(png|svg|jpg|jpeg|webp|woff2))"))throw new IllegalArgumentException("Unsupported template resource: "+name);
   byte[] data=zip.readNBytes(1_000_001);total+=data.length;if(data.length>1_000_000||total>4_000_000)throw new IllegalArgumentException("Expanded template exceeds resource limits.");files.put(name,data);
  }}catch(IOException e){throw new IllegalArgumentException("Invalid template ZIP.",e);}
  if(!files.containsKey("manifest.json")||!files.containsKey("index.html")||!files.containsKey("styles.css"))throw new IllegalArgumentException("Template requires manifest.json, index.html and styles.css.");
  LoaderOptions options=new LoaderOptions();options.setAllowDuplicateKeys(false);options.setMaxAliasesForCollections(0);options.setCodePointLimit(20000);
  Object parsed=new Yaml(new SafeConstructor(options)).load(new String(files.get("manifest.json"),java.nio.charset.StandardCharsets.UTF_8));if(!(parsed instanceof Map<?,?> raw))throw new IllegalArgumentException("Invalid template manifest.");
  Map<String,Object> m=new LinkedHashMap<>();raw.forEach((k,v)->m.put(String.valueOf(k),v));
  if(!Objects.equals(m.get("schemaVersion"),1)||!Objects.toString(m.get("id"),"").matches("[A-Za-z][A-Za-z0-9_-]{0,48}")||!Objects.toString(m.get("version"),"").equals("1")||!Objects.equals(m.get("html"),"index.html")||!Objects.equals(m.get("css"),"styles.css"))throw new IllegalArgumentException("Unsupported template manifest or resource names.");
  TemplateSettings.validateSchema(m.get("settingsSchema"));
  boolean executable=Boolean.TRUE.equals(m.get("executable"));if(executable&&!trustedExecutable)throw new IllegalArgumentException("Executable templates require explicit trustedExecutable acknowledgement.");
  if(files.containsKey("renderer.js")&&(!executable||!Objects.equals(m.get("renderer"),"renderer.js")))throw new IllegalArgumentException("Renderer JavaScript requires executable:true and renderer:renderer.js.");
  String html=new String(files.get("index.html"),java.nio.charset.StandardCharsets.UTF_8),css=new String(files.get("styles.css"),java.nio.charset.StandardCharsets.UTF_8);
  if(!executable&&validationHtml(html).matches("(?is).*(<script|on[a-z]+\\s*=|javascript:|<iframe|<object|<embed|<form|<meta|<base|http:|https:|[\"']\\s*//|srcdoc).*"))throw new IllegalArgumentException("Declarative templates cannot contain executable or remote content.");
  if(css.matches("(?is).*(https?:|@import|javascript:).*"))throw new IllegalArgumentException("Remote template CSS resources are unsupported.");
  if(!executable)files.put("index.html",(html+"<link rel=\"stylesheet\" href=\"styles.css\"><script src=\"/ui/template-bridge.js\"></script>").getBytes(java.nio.charset.StandardCharsets.UTF_8));
  List<?> assets=m.get("assets") instanceof List<?> a?a:List.of();for(Object asset:assets)if(!(asset instanceof String s)||!s.startsWith("assets/")||!files.containsKey(s))throw new IllegalArgumentException("Missing template asset.");
  try{String digest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(archive)).substring(0,16);return new TemplateBundle(m.get("id")+"-"+digest,Collections.unmodifiableMap(m),Collections.unmodifiableMap(files));}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
 }
 private static String validationHtml(String html){
  // Only these inert metadata forms are allowed. Refresh, policy and remote metadata remain rejected.
  for(String allowed:List.of("<meta charset=\"utf-8\">","<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"))html=html.replaceAll("(?i)"+java.util.regex.Pattern.quote(allowed),"");
  return html;
 }
 public Map<String,Object> descriptor(){Map<String,Object> descriptor=new LinkedHashMap<>(Map.of("id",id,"name",Objects.toString(manifest.get("name"),id),"version","1","description",Objects.toString(manifest.get("description"),""),"executable",Boolean.TRUE.equals(manifest.get("executable")),"rendererUrl","/ui/templates/"+id+"/index.html"));descriptor.put("bundleUrl","/api/v1/templates/"+id+"/bundle");descriptor.put("theme",Objects.toString(manifest.get("theme"),"custom"));if(manifest.get("settingsSchema")!=null)descriptor.put("settingsSchema",manifest.get("settingsSchema"));return Collections.unmodifiableMap(descriptor);}
 public void copyTo(Path destination)throws IOException{Files.createDirectories(destination);for(var f:files.entrySet()){Path target=destination.resolve(f.getKey()).normalize();if(!target.startsWith(destination.normalize()))throw new IllegalArgumentException("Invalid template target");Files.createDirectories(target.getParent());Files.write(target,f.getValue(),StandardOpenOption.CREATE_NEW);}}
}

