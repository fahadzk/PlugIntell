"""Explicit standalone wiring shared by the Python launcher's capability combinations."""
from __future__ import annotations
import json
import math
import re
import shutil
import hashlib
import zipfile
from pathlib import Path
import template_settings

BUILTINS = {
    "ExtractTextPlugin": "extract-text", "PdfOcrToTextPlugin": "pdf-ocr-to-text",
    "CitationTextSplitterPlugin": "citation-text-splitter", "TikaDocumentSplitterPlugin": "document-splitter",
    "StaticSplitterPlugin": "static-splitter", "OllamaEmbeddingPlugin": "ollama-embedding",
    "Neo4jWriterPlugin": "neo4j-writer", "Neo4jRetrievalPlugin": "neo4j-retrieval",
    "OllamaAnswerPlugin": "ollama-answer", "TestEmbedderPlugin": "test-embedder",
    "TestWriterPlugin": "test-writer", "DocumentMergePlugin": "document-merge",
}

def java(value):
    if value is None: return "null"
    if isinstance(value, bool): return str(value).lower()
    if isinstance(value, int): return str(value) if -(2**31)<=value<2**31 else str(value)+"L" if -(2**63)<=value<2**63 else "new java.math.BigInteger("+json.dumps(str(value))+")"
    if isinstance(value, float): return repr(value) + "d"
    if isinstance(value, str): return json.dumps(value, ensure_ascii=True)
    if isinstance(value, list): return "List.of(" + ",".join(map(java, value)) + ")"
    if isinstance(value, dict): return "Map.ofEntries(" + ",".join("Map.entry(" + java(k) + "," + java(v) + ")" for k, v in value.items()) + ")"
    raise ValueError("Unsupported configuration value")

def metadata(classes):
    if not classes or any(not isinstance(cls,str) or not re.fullmatch(r"[A-Za-z_$][A-Za-z0-9_$]*(\.[A-Za-z_$][A-Za-z0-9_$]*)+",cls) for cls in classes): raise ValueError("Plugin constructors require validated fully qualified Java class names.")
    return [(f"node-{i+1}", BUILTINS.get(cls.rsplit(".",1)[-1], cls), cls) for i, cls in enumerate(classes)]

def render(package, main_class, lanes, capabilities, reporting_plugins=()):
    host = capabilities.get("host", {"type":"cli"})
    web = host.get("type") == "micronaut-web"
    template = capabilities.get("template", {"id":"standard","version":"1","settings":{}})
    assembly = [f"package {package};", "import java.util.*;", "import com.core.docintel.pipeline.api.*;", "import com.core.docintel.pipeline.core.*;", "import com.core.docintel.pipeline.events.*;", "public final class PipelineAssembly {",
                "public static ApplicationServices create(){return create(true,5);}",
                "public static ApplicationServices create(boolean citations,int topK){",
                "Map<String,ApplicationServices.Workflow> workflows=new LinkedHashMap<>();Map<String,PipelineDescriptor> descriptors=new LinkedHashMap<>();"]
    for mode, classes in lanes.items():
        nodes=metadata(classes)
        descriptors="List.of("+",".join(f"new PipelineDescriptor.Node({java(node)},{java(pid)},{java(pid)},List.of())" for node,pid,cls in nodes)+")"
        edges="List.of("+",".join(f"new PipelineDescriptor.Edge({java(nodes[i-1][0])},{java(nodes[i][0])})" for i in range(1,len(nodes)))+")"
        assembly.append(f"descriptors.put({java(mode)},new PipelineDescriptor(null,null,{java(mode)}, {descriptors},{edges}));")
        instances=[]
        for node,pid,cls in nodes:
            reporter=f"events.prepareAttempt({java(node)},{java(pid)},1)" if cls in reporting_plugins or cls == "com.core.docintel.plugins."+cls.rsplit(".",1)[-1] and cls.rsplit(".",1)[-1] in BUILTINS else ""
            instances.append(f"new {cls}({reporter})")
        input_code = 'new SearchRequest(input,citations,topK)' if mode == "query" else 'inputFile(input)'
        assembly.append(f"workflows.put({java(mode)},(input,events)->{{List<Plugin> plugins=List.of({','.join(instances)});try(Pipeline pipeline=new Pipeline(plugins,events,{java([n[0] for n in nodes])})){{Object value={input_code};new PipelineValidator().validate(plugins);return pipeline.execute(value);}}}});")
    assembly.append("ApplicationServices.Adapters wiring=(mode,dispatcher,descriptor,state)->{")
    for adapter in capabilities.get("adapters",[]):
        relevant=[s for s in capabilities.get("subscriptions",[]) if s["adapterId"]==adapter["id"]]
        for mode in lanes:
            workflow="QUERY_PROCESSING" if mode=="query" else "DOCUMENT_INGESTION"
            selected=[s for s in relevant if s["workflow"]==workflow]
            if not selected: continue
            filters="List.of("+",".join("new EventFilter(Set.copyOf("+java(s.get("nodeIds",[]))+"),Set.copyOf("+java(s.get("eventTypes",[]))+"),Set.copyOf("+java(s.get("severities",[]))+"))" for s in selected)+")"
            kind=adapter["type"]
            ctor={"ui":"new UIMessageAdapter(descriptor,state)","noop":"new NoopEventAdapter()"}.get(kind)
            if kind=="kafka": ctor="new com.core.docintel.pipeline.kafka.KafkaEventAdapter(com.core.docintel.pipeline.kafka.KafkaConfig.from("+java(adapter.get("config",{}))+"))"
            assembly.append(f"if(mode.equals({java(mode)}))dispatcher.attachLazy({java(adapter['id'])},()->{ctor},{filters});")
    assembly.append("};Map<String,List<EventFilter>> views=new LinkedHashMap<>();")
    ui_ids={a["id"] for a in capabilities.get("adapters",[]) if a["type"]=="ui"}
    for mode in lanes:
        workflow="QUERY_PROCESSING" if mode=="query" else "DOCUMENT_INGESTION"
        subs=[s for s in capabilities.get("subscriptions",[]) if s["adapterId"] in ui_ids and s["workflow"]==workflow]
        view_filters="List.of("+",".join("new EventFilter(Set.copyOf("+java(s.get("nodeIds",[]))+"),Set.copyOf("+java(s.get("eventTypes",[]))+"),Set.copyOf("+java(s.get("severities",[]))+"))" for s in subs)+")"
        assembly.append(f"views.put({java(mode)},{view_filters});")
    assembly.extend([f"return new ApplicationServices(workflows,descriptors,wiring,{java(template)},views);","}",
        'private static java.io.File inputFile(String value){java.io.File file=new java.io.File(value);if(!file.isFile()||!file.canRead())throw new IllegalArgumentException("Input file is not readable.");return file;}',"}"])
    serve=""
    if web:
        serve=f'''if(args.length==1&&args[0].equals("serve")){{try(var services=PipelineAssembly.create();var host=new com.core.docintel.pipeline.web.MicronautWebAppAdapter(new com.core.docintel.pipeline.web.WebConfig({int(host.get("port",8080))},{java(host.get("openBrowser",False))},java.nio.file.Path.of(System.getenv().getOrDefault("PIPELINE_INPUT_ROOT","data/ingestion"))),services)){{host.serve();}}return;}}'''
    source=f'''package {package};
import java.util.*;
import com.core.docintel.pipeline.events.*;
public final class {main_class}{{
 public static void main(String[] args){{
  try{{
   {serve}
   String mode,input;boolean citations=true;int topK=5;
   if(args.length>0&&args[0].equals("--help")){{System.out.println("Usage: {'ingest <file> | query [--no-citations] [--top-k=N]' if 'query' in lanes else '[input-file]'}{' | serve' if web else ''}");return;}}
   if(args.length>0&&args[0].equals("query")){{
    mode="query";for(int i=1;i<args.length;i++){{if(args[i].equals("--no-citations"))citations=false;else if(args[i].startsWith("--top-k="))topK=Integer.parseInt(args[i].substring(8));else throw new IllegalArgumentException("Unknown query option.");}}
    if(topK<1||topK>100)throw new IllegalArgumentException("top-k must be 1..100.");
    System.out.print("Search query: ");Scanner scanner=new Scanner(System.in);if(!scanner.hasNextLine())throw new IllegalArgumentException("No query entered.");input=scanner.nextLine().trim();if(input.isBlank())throw new IllegalArgumentException("Query is blank.");
   }}else if(args.length>0&&args[0].equals("ingest")){{if(args.length!=2)throw new IllegalArgumentException("Usage: ingest <file>");mode="ingestion";input=args[1];}}
   else{{if(args.length>1)throw new IllegalArgumentException("Unexpected arguments.");mode="ingestion";input=args.length==0?"sample.txt":args[0];}}
   try(var services=PipelineAssembly.create(citations,topK)){{var session=services.create(mode);Object result;try{{result=services.execute(session,input);}}finally{{EventDiagnostics.report(session.dispatcher.health(),false,System.err);}}System.out.println("Pipeline completed successfully. Result: "+result);}}
  }}catch(Exception error){{System.err.println("Pipeline failed: "+new EventSanitizer().text(Objects.toString(error.getMessage(),error.getClass().getSimpleName())));System.exit(1);}}
 }}
}}
'''
    return source,"\n".join(assembly)+"\n"

def _check_values(value, depth=0):
    if depth>8 or value is None: raise ValueError("Capability values must be non-null and within nesting limits.")
    if isinstance(value,dict):
        if len(value)>128 or any(not isinstance(k,str) for k in value): raise ValueError("Invalid capability object.")
        for item in value.values(): _check_values(item,depth+1)
    elif isinstance(value,list):
        if len(value)>256: raise ValueError("Capability list exceeds its limit.")
        for item in value: _check_values(item,depth+1)
    elif isinstance(value,float) and not math.isfinite(value): raise ValueError("Capability numbers must be finite.")
    elif not isinstance(value,(str,int,float,bool)): raise ValueError("Unsupported capability value.")

def validate(capabilities, lanes, custom_template=None):
    _check_values(capabilities)
    if not isinstance(capabilities,dict) or set(capabilities)-{"adapters","subscriptions","host","template"}: raise ValueError("Invalid capability settings.")
    adapters=capabilities.get("adapters",[]); ids=set()
    if not isinstance(adapters,list) or len(adapters)>128 or any(not isinstance(a,dict) for a in adapters): raise ValueError("Invalid adapters list.")
    for a in adapters:
        if set(a)-{"id","type","version","config"} or not re.fullmatch(r"[A-Za-z][A-Za-z0-9_-]{0,63}",a.get("id","")) or a["id"] in ids: raise ValueError("Invalid/duplicate adapter ID.")
        ids.add(a["id"])
        if a.get("type") not in ("ui","kafka","noop") or a.get("version","1")!="1": raise ValueError("Unsupported adapter.")
        config=a.get("config",{})
        if not isinstance(config,dict): raise ValueError("Adapter configuration must be an object.")
        if a["type"]=="kafka":
            if set(config)-{"bootstrapServers","topic","clientId","securityProtocol","saslMechanism","username","password","truststoreLocation","truststorePassword"} or not config.get("bootstrapServers") or not re.fullmatch(r"[A-Za-z0-9_.-]{1,249}",config.get("topic","")): raise ValueError("Invalid Kafka settings.")
            for key in ("username","password","truststorePassword"):
                if config.get(key) and not re.fullmatch(r"secret://env/[A-Za-z_][A-Za-z0-9_]*",config[key]): raise ValueError("Kafka credentials must be environment secret references.")
        elif config: raise ValueError("This adapter has no settings.")
    host=capabilities.get("host",{"type":"cli"})
    if not isinstance(host,dict) or set(host)-{"type","port","openBrowser"} or host.get("type","cli") not in ("cli","micronaut-web") or type(host.get("port",8080)) is not int or not 1<=host.get("port",8080)<=65535 or not isinstance(host.get("openBrowser",False),bool): raise ValueError("Invalid host settings.")
    if any(a["type"]=="ui" for a in adapters) and host.get("type")!="micronaut-web": raise ValueError("UI adapter requires --web.")
    for s in capabilities.get("subscriptions",[]):
        mode={"DOCUMENT_INGESTION":"ingestion","QUERY_PROCESSING":"query"}.get(s.get("workflow"))
        if set(s)-{"adapterId","workflow","nodeIds","eventTypes","severities"} or s.get("adapterId") not in ids or mode not in lanes: raise ValueError("Invalid event subscription.")
        valid_nodes={n[0] for n in metadata(lanes[mode])}
        if set(s.get("nodeIds",[]))-valid_nodes or any(not re.fullmatch(r"[A-Z_]{1,64}",x) for x in s.get("eventTypes",[])) or set(s.get("severities",[]))-{"INFO","WARN","ERROR","TRACE","DEBUG"}: raise ValueError("Invalid subscription filters.")
    template=capabilities.get("template",{"id":"standard","version":"1"})
    if set(template)-{"id","version","settings"} or template.get("version","1")!="1" or template.get("id","standard") not in ("standard","water",custom_template["id"] if custom_template else None) or len(template.get("settings",{}))>32 or any(not isinstance(v,(str,int,float,bool)) for v in template.get("settings",{}).values()): raise ValueError("Invalid template settings; supply --template-bundle for imported templates.")
    template_settings.validate(template.get("settings",{}),custom_template["manifest"].get("settingsSchema") if custom_template and template.get("id")==custom_template["id"] else None)

def bundle(project, capabilities, lanes, custom_template=None):
    root=Path(__file__).resolve().parents[1]
    resources=project/"src/main/resources";resources.mkdir(parents=True,exist_ok=True)
    descriptor={"schemaVersion":1,"capabilities":capabilities,"workflows":{mode:{"nodes":[{"id":n,"pluginId":p,"label":p,"capabilities":[]} for n,p,c in metadata(classes)],"edges":[{"from":f"node-{i}","to":f"node-{i+1}"} for i in range(1,len(classes))]} for mode,classes in lanes.items()}}
    (resources/"pipeline-descriptor.json").write_text(json.dumps(descriptor,indent=2),encoding="utf-8")
    if capabilities.get("host",{}).get("type")=="micronaut-web":
        public=resources/"public";public.mkdir(exist_ok=True)
        for name in ("monitor.html","monitor.css","monitor.js","template-bridge.js"): shutil.copyfile(root/"pipeline-builder-ui"/name,public/name)
        if custom_template and capabilities.get("template",{}).get("id")==custom_template["id"]:
            for name,data in custom_template["files"].items():
                target=public/"templates"/custom_template["id"]/name
                target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(data)
    artifact=re.sub(r"[^A-Za-z0-9_.-]","-",project.name).lower()
    web=capabilities.get("host",{}).get("type")=="micronaut-web"
    commands=(["serve"] if web else [])+(["ingest document.txt"] if "ingestion" in lanes else [])+(['query --top-k=5'] if "query" in lanes else [])+["--help"]
    batch=f'@echo off\nsetlocal\npushd "%~dp0" || exit /b 1\nif "%~1"=="" goto usage\nset "PIPELINE_JAVA=java"\nif defined JAVA_HOME set "PIPELINE_JAVA=%JAVA_HOME%\\bin\\java.exe"\n"%PIPELINE_JAVA%" -jar "target\\{artifact}-1.0.0.jar" %*\nset "PIPELINE_EXIT=%ERRORLEVEL%"\npopd\nexit /b %PIPELINE_EXIT%\n:usage\necho Usage:\n'+''.join('echo   run.bat '+command+'\n' for command in commands)+'popd\nexit /b 0\n'
    (project/"run.bat").write_bytes(batch.replace('\n','\r\n').encode('utf-8'))
    shell='#!/bin/sh\ncd "$(dirname "$0")" || exit 1\nif [ "$#" -eq 0 ]; then\ncat <<\'USAGE\'\nUsage:\n'+''.join('  ./run.sh '+command+'\n' for command in commands)+'USAGE\nexit 0\nfi\nPIPELINE_JAVA=java\nif [ -n "${JAVA_HOME:-}" ]; then PIPELINE_JAVA="$JAVA_HOME/bin/java"; fi\n'+f'exec "$PIPELINE_JAVA" -jar target/{artifact}-1.0.0.jar "$@"\n'
    (project/"run.sh").write_text(shell,encoding="utf-8")
    readme="# Generated pipeline\n\nBuild with Java 21: `mvn package`. Keep the packaged dependencies together. Run `run.bat` without arguments to list available options. Scripts use the project directory and prefer JAVA_HOME.\n\n## Run options\n\n```bat\n"+'\n'.join('run.bat '+command for command in commands)+"\n```\n\nOn Linux/macOS use `sh run.sh` with the same arguments. Direct Java commands:\n\n```shell\n"+'\n'.join('java -jar target/'+artifact+'-1.0.0.jar '+command for command in commands)+"\n```\n"
    if web:readme+="\n## Web UI and server logs\n\n`run.bat serve` starts the Micronaut host, prints the local URL and serves multiple runs until Ctrl+C. Local UI view settings are compiled from the selected template pin. External assets are under src/main/resources/public/templates/. Change the view through the generator capabilities/ZIP options and rebuild; editing the descriptor alone does not alter compiled wiring. The generated page displays the final query answer and citation source/page metadata in **Query answer** after success. Open a historical execution to restore its answer. Answers are fetched separately from telemetry and are never sent to custom templates or structured logs. Retention is memory-only: at most 100 runs, 262,144 answer characters and 100 citations per result; eviction or restart removes results. Citation document text is excluded. AnswerResult and plain String query outputs are supported; other output types keep their safe monitor summaries. Ingestion files must be inside PIPELINE_INPUT_ROOT (default data/ingestion; create the directory if needed). Server lifecycle and structured execution logs appear in the same terminal and in logs/server-0.log, rotating through three files of 5 MB. PIPELINE_LOG_DIR changes the directory. Run/node/attempt IDs distinguish concurrent work. Documents, prompts, answers and embedding arrays are excluded from log payloads. Direct plugin stdout/stderr remains in the console.\n\nFollow the file in PowerShell: `Get-Content .\\logs\\server-0.log -Wait -Tail 100`. To capture console output too, create logs then use `run.bat serve > logs\\console.log 2>&1`.\n"
    else:readme+="\nCLI only; no serve command or web dependencies.\n"
    if not any(adapter["type"]=="kafka" for adapter in capabilities.get("adapters",[])):
        native_batch=batch.replace('set "PIPELINE_JAVA=java"\nif defined JAVA_HOME set "PIPELINE_JAVA=%JAVA_HOME%\\bin\\java.exe"\n"%PIPELINE_JAVA%" -jar "target\\'+artifact+'-1.0.0.jar" %*','"target\\'+artifact+'.exe" %*').replace('run.bat ','run-native.bat ')
        (project/"run-native.bat").write_bytes(native_batch.replace('\n','\r\n').encode('utf-8'))
        native_shell=shell[:shell.index('PIPELINE_JAVA=java')]+f'exec ./target/{artifact} "$@"\n'
        (project/"run-native.sh").write_text(native_shell.replace('./run.sh ','./run-native.sh '),encoding="utf-8")
        readme+="\n## Native executable\n\nBuild with GraalVM JDK 21 and the host C/C++ toolchain: `mvn -Pnative package`. Run `run-native.bat` for help, or `sh run-native.sh` on Linux/macOS. These scripts execute target/"+artifact+" (.exe on Windows), without Java or external JARs at runtime. Keep any GraalVM-emitted DLL/shared-library files beside the executable, especially when document parsers use AWT/image codecs. Keep the project dependencies for rebuilding."
        if web:readme+=" Start the web UI with `run-native.bat serve` (or `sh run-native.sh serve`). The selected template and UI resources are embedded in the executable; ingestion/query, snapshots, answers, SSE and rolling server logs use the same protocol as the JVM host."
        readme+="\n"
    readme+="\nEvent wiring is explicit in PipelineAssembly.java. Kafka requires repository artifacts built with -Pkafka, environment secret references and broker connectivity. Delivery is bounded and best effort, not exactly once. Standalone history is memory-only (100 runs, 5,000 events per run). Kafka builds have no supported native profile; CLI and Micronaut web/UI projects include it. Selected processing plugins still need native-compatible dependencies and reachability metadata.\n"
    (project/"README.md").write_text(readme,encoding="utf-8")
    (project/".gitignore").write_text("target/\nlogs/\n.env\n",encoding="utf-8")


def resolve_template(identifier,provided=None):
    if provided:return provided
    if not isinstance(identifier,str):raise ValueError("Template id must be a string.")
    if identifier in ("standard","water"):return None
    for name in ("aurora","midnight"):
        if identifier!=name and not identifier.startswith(name+"-"):continue
        bundle=read_template(Path(__file__).resolve().parents[1]/"pipeline-builder-ui/templates"/(name+".zip"))
        if identifier in (name,bundle["id"]):return bundle
    return None

def read_template(path, trusted=False):
    data=path.read_bytes()
    if len(data)>2_000_000: raise ValueError("Template archive exceeds 2 MB.")
    import io
    files={};total=0
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        for item in archive.infolist():
            if item.is_dir(): continue
            name=item.filename
            if not re.fullmatch(r"[A-Za-z0-9_./-]{1,180}",name) or name.startswith("/") or any(p in (".","..","") for p in name.split("/")): raise ValueError("Unsafe template archive path.")
            if name!="manifest.json" and not re.fullmatch(r"(index\.html|styles\.css|renderer\.js|assets/[A-Za-z0-9_./-]+\.(png|svg|jpg|jpeg|webp|woff2))",name): raise ValueError("Unsupported template resource.")
            if name in files or len(files)>=100 or item.file_size>1_000_000: raise ValueError("Invalid template resource limits.")
            value=archive.read(item);total+=len(value)
            if total>4_000_000: raise ValueError("Expanded template exceeds 4 MB.")
            files[name]=value
    if not {"manifest.json","index.html","styles.css"} <= files.keys(): raise ValueError("Template resources are missing.")
    manifest=json.loads(files["manifest.json"])
    if type(manifest.get("schemaVersion")) is not int or manifest.get("schemaVersion")!=1 or manifest.get("version")!="1" or not re.fullmatch(r"[A-Za-z][A-Za-z0-9_-]{0,48}",manifest.get("id","")) or manifest.get("html")!="index.html" or manifest.get("css")!="styles.css": raise ValueError("Invalid template manifest.")
    template_settings.validate_schema(manifest.get("settingsSchema"))
    executable=manifest.get("executable",False)
    if executable and not trusted: raise ValueError("Executable template requires --trusted-template.")
    if "renderer.js" in files and (not executable or manifest.get("renderer")!="renderer.js"): raise ValueError("Renderer requires executable template declaration.")
    html=files["index.html"].decode("utf-8")
    validation_html=html
    for allowed in ('<meta charset="utf-8">','<meta name="viewport" content="width=device-width,initial-scale=1">'):
        validation_html=re.sub(re.escape(allowed),"",validation_html,flags=re.I)
    if not executable and re.search(r"<script|on[a-z]+\s*=|javascript:|<iframe|<object|<embed|<form|<meta|<base|https?:|[\"']\s*//|srcdoc",validation_html,re.I): raise ValueError("Declarative template cannot contain executable or remote content.")
    if re.search(r"https?:|@import|javascript:",files["styles.css"].decode("utf-8"),re.I): raise ValueError("Remote CSS resources are unsupported.")
    for asset in manifest.get("assets",[]):
        if not isinstance(asset,str) or not asset.startswith("assets/") or asset not in files: raise ValueError("Missing template asset.")
    if not executable: files["index.html"]=(html+'<link rel="stylesheet" href="styles.css"><script src="/ui/template-bridge.js"></script>').encode("utf-8")
    return {"id":manifest["id"]+"-"+hashlib.sha256(data).hexdigest()[:16],"manifest":manifest,"files":files}


