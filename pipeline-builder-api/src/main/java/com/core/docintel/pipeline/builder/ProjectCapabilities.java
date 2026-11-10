package com.core.docintel.pipeline.builder;
import java.util.*;
import java.nio.file.*;
import java.io.IOException;
import com.core.docintel.pipeline.events.*;
/** Trusted generation mappings. Processing and adapter constructors are emitted directly. */
final class ProjectCapabilities {
 static final Set<String> REPORTING=Set.of("ExtractTextPlugin","PdfOcrToTextPlugin","CitationTextSplitterPlugin","TikaDocumentSplitterPlugin","StaticSplitterPlugin","OllamaEmbeddingPlugin","Neo4jWriterPlugin","Neo4jRetrievalPlugin","OllamaAnswerPlugin","TestEmbedderPlugin","TestWriterPlugin","DocumentMergePlugin");
 static boolean reporting(PluginInfo p){return p.capabilities().contains("messages")||p.entrypoint().equals("com.core.docintel.plugins."+p.entrypoint().substring(p.entrypoint().lastIndexOf('.')+1))&&REPORTING.contains(p.entrypoint().substring(p.entrypoint().lastIndexOf('.')+1));}
 static boolean web(PipelineDefinition d){return "micronaut-web".equals(CapabilitySettings.map(d.capabilities()==null?null:d.capabilities().get("host")).get("type"));}
 static boolean kafka(PipelineDefinition d){return CapabilitySettings.objects(d.capabilities()==null?null:d.capabilities().get("adapters")).stream().anyMatch(a->"kafka".equals(a.get("type")));}
 static List<String> libraries(PipelineDefinition d){List<String> result=new ArrayList<>();if(web(d))result.add("pipeline-web");if(kafka(d))result.add("pipeline-event-kafka");return result;}
 static String value(Object value){
  if(value==null)return "null";if(value instanceof Map<?,?> m)return "Map.ofEntries("+String.join(",",m.entrySet().stream().map(e->"Map.entry("+value(String.valueOf(e.getKey()))+","+value(e.getValue())+")").toList())+")";
  if(value instanceof List<?> l)return "List.of("+String.join(",",l.stream().map(ProjectCapabilities::value).toList())+")";
  if(value instanceof Boolean||value instanceof Integer||value instanceof Short||value instanceof Byte)return value.toString();if(value instanceof Long)return value+"L";if(value instanceof Double)return value+"d";if(value instanceof Float)return value+"f";if(value instanceof java.math.BigDecimal)return "new java.math.BigDecimal("+value(value.toString())+")";if(value instanceof java.math.BigInteger)return "new java.math.BigInteger("+value(value.toString())+")";
  return EventJson.write(String.valueOf(value));
 }
 static String assembly(PipelineDefinition d,Map<PipelineType,List<PipelineNode>> lanes,Map<String,PluginInfo> metadata,String pipelineId,Integer pipelineVersion){
  StringBuilder s=new StringBuilder("package generated.pipeline;\nimport java.util.*;\nimport com.core.docintel.pipeline.events.*;\npublic final class PipelineAssembly {\npublic static ApplicationServices create(){\nMap<String,ApplicationServices.Workflow> workflows=new LinkedHashMap<>();Map<String,PipelineDescriptor> descriptors=new LinkedHashMap<>();\n");
  for(var lane:lanes.entrySet()){String mode=lane.getKey()==PipelineType.DOCUMENT_INGESTION?"ingestion":"query",name=lane.getKey()==PipelineType.DOCUMENT_INGESTION?"IngestionPipeline":"QueryPipeline";s.append("workflows.put(").append(value(mode)).append(",").append(name).append("::run);\ndescriptors.put(").append(value(mode)).append(",new PipelineDescriptor(").append(value(pipelineId)).append(",").append(value(pipelineVersion)).append(",").append(value(lane.getKey().name())).append(",List.of(");
   s.append(String.join(",",lane.getValue().stream().map(n->{PluginInfo p=metadata.get(n.pluginId());return "new PipelineDescriptor.Node("+value(n.id())+","+value(n.pluginId())+","+value(p.name())+","+value(p.capabilities())+")";}).toList())).append("),List.of(");
   List<PipelineEdge> edges=d.workflow(lane.getKey()).edges();s.append(String.join(",",edges.stream().map(e->"new PipelineDescriptor.Edge("+value(e.from())+","+value(e.to())+")").toList())).append(")));\n");
  }
  s.append("ApplicationServices.Adapters wiring=(mode,dispatcher,descriptor,state)->{switch(mode){\n");
  Map<String,Object> c=d.capabilities()==null?Map.of():d.capabilities();
  for(PipelineType type:lanes.keySet()){String mode=type==PipelineType.DOCUMENT_INGESTION?"ingestion":"query";s.append("case ").append(value(mode)).append(" -> {\n");
   for(Map<String,Object> a:CapabilitySettings.objects(c.get("adapters"))){String id=CapabilitySettings.text(a,"id",""),kind=CapabilitySettings.text(a,"type","");List<EventFilter> filters=CapabilitySettings.filters(c,id,type);if(filters.isEmpty())continue;String ctor=switch(kind){case "ui"->"new UIMessageAdapter(descriptor,state)";case "noop"->"new NoopEventAdapter()";case "kafka"->"new com.core.docintel.pipeline.kafka.KafkaEventAdapter(com.core.docintel.pipeline.kafka.KafkaConfig.from("+value(CapabilitySettings.map(a.get("config")))+"))";default->throw new IllegalArgumentException("Unknown adapter.");};
    s.append("dispatcher.attachLazy(").append(value(id)).append(",()->").append(ctor).append(",List.of(").append(String.join(",",filters.stream().map(f->"new EventFilter(Set.copyOf("+value(new ArrayList<>(f.nodeIds()))+"),Set.copyOf("+value(new ArrayList<>(f.eventTypes()))+"),Set.copyOf("+value(new ArrayList<>(f.severities()))+"))").toList())).append("));\n");
   }s.append("}\n");
  }
  s.append("default -> throw new IllegalArgumentException(\"Unknown workflow.\");}};\nMap<String,List<EventFilter>> views=new LinkedHashMap<>();\n");
  for(PipelineType type:lanes.keySet())s.append("views.put(").append(value(type==PipelineType.DOCUMENT_INGESTION?"ingestion":"query")).append(",").append(filterSource(CapabilitySettings.uiFilters(c,type))).append(");\n");
  s.append("return new ApplicationServices(workflows,descriptors,wiring,").append(value(CapabilitySettings.map(c.get("template")))).append(",views);\n}\n}\n");return s.toString();
 }
 static String filterSource(List<EventFilter> filters){return "List.of("+String.join(",",filters.stream().map(f->"new EventFilter(Set.copyOf("+value(new ArrayList<>(f.nodeIds()))+"),Set.copyOf("+value(new ArrayList<>(f.eventTypes()))+"),Set.copyOf("+value(new ArrayList<>(f.severities()))+"))").toList())+")";}
 static String main(PipelineDefinition d,Set<String> secretNames){
  String serve=web(d)?"""
    if(args.length==1&&args[0].equals("serve")){
      Map<String,Object> host=HOST_SETTINGS;
      try(var adapter=new com.core.docintel.pipeline.web.MicronautWebAppAdapter(new com.core.docintel.pipeline.web.WebConfig(((Number)host.getOrDefault("port",8080)).intValue(),Boolean.TRUE.equals(host.get("openBrowser")),java.nio.file.Path.of(System.getenv().getOrDefault("PIPELINE_INPUT_ROOT","data/ingestion"))),services)){adapter.serve();}return;
    }
    """.replace("HOST_SETTINGS",value(CapabilitySettings.map(d.capabilities().get("host")))):"";
  return """
    package generated.pipeline;
    import java.util.*;
    import com.core.docintel.pipeline.events.*;
    public final class GeneratedPipelineMain {
      static {RuntimeSupport.registerSecretNames(Set.of(SECRET_NAMES));}
      public static void main(String[] args){
        boolean debug=Arrays.asList(args).contains("--debug");
        try(ApplicationServices services=PipelineAssembly.create()){
          SERVE_CODE
          Map<String,String> flags=new HashMap<>();
          for(int i=0;i<args.length;i++){
            String flag=args[i];
            if(flag.equals("--help")){System.out.println("USAGE_TEXT");return;}
            if(flag.equals("--debug"))continue;
            if(!Set.of("--mode","--input","--query").contains(flag)||i+1>=args.length||flags.putIfAbsent(flag,args[++i])!=null)throw new IllegalArgumentException("Invalid, missing, or duplicate flag: "+flag);
          }
          String mode=RuntimeSupport.required(flags,"--mode");
          if(!Set.of("ingestion","query").contains(mode))throw new IllegalArgumentException("Unknown mode.");
          if(flags.containsKey(mode.equals("ingestion")?"--query":"--input"))throw new IllegalArgumentException("Input flag does not match mode.");
          if(debug)RuntimeSupport.printEnvironment();
          String input=RuntimeSupport.required(flags,mode.equals("ingestion")?"--input":"--query");
          var session=services.create(mode);Object result;
          try{result=services.execute(session,input);}finally{EventDiagnostics.report(session.dispatcher.health(),debug,System.err);}
          System.out.println("Pipeline completed successfully. Result: "+result);
        }catch(Exception error){RuntimeSupport.report(error,debug);System.exit(1);}
      }
    }
    """.replace("SECRET_NAMES",String.join(",",secretNames.stream().map(ProjectCapabilities::value).toList())).replace("SERVE_CODE",serve).replace("USAGE_TEXT","Usage: --mode ingestion --input <file> | --mode query --query <question> [--debug]"+(web(d)?" | serve":""));
 }
 static String workflow(PipelineType type,List<PipelineNode> nodes,Map<String,PluginInfo> metadata){
  String name=type==PipelineType.DOCUMENT_INGESTION?"IngestionPipeline":"QueryPipeline";
  StringBuilder s=new StringBuilder("package generated.pipeline;\nimport com.core.docintel.pipeline.api.*;\nimport com.core.docintel.pipeline.core.*;\nimport com.core.docintel.pipeline.events.*;\nimport java.util.*;\npublic final class "+name+"{public static Object run(String value,RunEvents events){\n");
  if(type==PipelineType.DOCUMENT_INGESTION)s.append("java.io.File input=RuntimeSupport.inputFile(value);\n");
  s.append("List<Plugin> plugins=List.of(\n");
  for(int i=0;i<nodes.size();i++){PipelineNode node=nodes.get(i);PluginInfo p=metadata.get(node.pluginId());if(!p.entrypoint().matches("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)+"))throw new IllegalArgumentException("Invalid plugin entrypoint.");s.append("new ").append(p.entrypoint()).append("(");if(reporting(p))s.append("events.prepareAttempt(").append(value(node.id())).append(",").append(value(node.pluginId())).append(",1)");s.append(")").append(i<nodes.size()-1?",\n":"\n");}
  s.append(");\ntry(Pipeline pipeline=new Pipeline(plugins,events,").append(value(nodes.stream().map(PipelineNode::id).toList())).append(")){\n");
  for(int i=0;i<nodes.size();i++)s.append("RuntimeSupport.configure(plugins.get(").append(i).append("),").append(value(nodes.get(i).config()==null?Map.of():nodes.get(i).config())).append(",events);\n");
  s.append("new PipelineValidator().validate(plugins);\nreturn pipeline.execute(").append(type==PipelineType.DOCUMENT_INGESTION?"input":"new SearchRequest(value,Boolean.parseBoolean(System.getenv().getOrDefault(\"RAG_SEARCH_INCLUDE_CITATIONS\",\"true\")),Integer.parseInt(System.getenv().getOrDefault(\"RAG_SEARCH_TOP_K\",\"5\")))").append(");}}}\n");return s.toString();
 }
}

