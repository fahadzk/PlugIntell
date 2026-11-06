package com.core.docintel.pipeline.builder;

import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;
import com.core.docintel.pipeline.api.ManagedPlugin;
import com.core.docintel.pipeline.api.Plugin;
import com.core.docintel.pipeline.core.PluginLoader;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

@Singleton
public final class PluginCatalog {
    private final Path pluginRoot;
    private volatile List<PluginInfo> cached;
    private final Map<String, Path> pluginDirectories = new java.util.concurrent.ConcurrentHashMap<>();

    public PluginCatalog(@Value("${pipeline.builder.plugin-root:}") String configuredRoot) {
        this.pluginRoot = locateRoot(configuredRoot);
    }

    public List<PluginInfo> all() {
        List<PluginInfo> result = cached;
        if (result == null) synchronized (this) {
            result = cached;
            if (result == null) cached = result = discover();
        }
        return result;
    }

    /** Re-scan trusted local plugin manifests so newly installed plugins are visible without restarting. */
    public synchronized List<PluginInfo> reload() {
        pluginDirectories.clear();
        cached = null;
        return all();
    }

    public List<PluginDescriptor> descriptors() { return all().stream().map(this::descriptor).toList(); }

    public Optional<PluginDescriptor> descriptor(String id) {
        return all().stream().filter(plugin -> plugin.id().equals(id)).findFirst().map(this::descriptor);
    }

    private PluginDescriptor descriptor(PluginInfo plugin) {
        Map<String,Object> properties = new LinkedHashMap<>();
        Map<String,Object> ui = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        int order = 0;
        for (ConfigOption option : plugin.configuration()) {
            Map<String,Object> field = new LinkedHashMap<>();
            field.put("type", option.type()); field.put("title", option.name());
            field.put("description", option.description());
            if (!option.defaultValue().isBlank() && !option.sensitive()) field.put("default", defaultValue(option));
            if(option.minimum()!=null)field.put("minimum",option.minimum());if(option.maximum()!=null)field.put("maximum",option.maximum());
            if (option.sensitive()) { field.put("format", "secret-reference"); field.put("writeOnly", true); }
            properties.put(option.name(), field);
            if (option.required()) required.add(option.name());
            ui.put(option.name(), Map.of("widget", option.sensitive() ? "secret-reference" : "text", "order", order++));
        }
        Map<String,Object> schema = new LinkedHashMap<>();
        schema.put("$schema", "https://json-schema.org/draft/2020-12/schema");
        schema.put("type", "object"); schema.put("title", plugin.name()+" configuration");
        schema.put("properties", properties); schema.put("additionalProperties", false);
        if (!required.isEmpty()) schema.put("required", required);
        String category = category(plugin);
        return new PluginDescriptor(plugin.id(), plugin.name(), plugin.version(), category, plugin.description(),
                plugin.apiVersion(), plugin.capabilities(),
                List.of(new PluginPort("input", "Input", plugin.inputType(), plugin.capabilities().contains("fan-in") ? "MANY" : "ONE")),
                List.of(new PluginPort("output", "Output", plugin.outputType(), "ONE")), schema, ui);
    }

    private static String category(PluginInfo plugin) {
        String id=plugin.id();
        if(id.contains("embed"))return "EMBEDDING_MODEL";
        if(id.contains("retrieval"))return "RETRIEVER";
        if(id.contains("writer"))return "VECTOR_STORE";
        if(id.contains("splitter"))return "CHUNKER";
        if(id.contains("answer"))return "LLM_PROVIDER";
        if(id.contains("text")||id.contains("ocr"))return "DOCUMENT_PARSER";
        return "DOCUMENT_TRANSFORMER";
    }

    public synchronized Path pluginJar(PluginInfo plugin) {
        Path module = pluginDirectories.get(plugin.id());
        if (module == null) throw new IllegalArgumentException("No source module is registered for plugin '" + plugin.id() + "'.");
        Path path = module.resolve("target").resolve(plugin.artifactId() + ".jar").normalize();
        if (!path.startsWith(pluginRoot) || !Files.isRegularFile(path))
            throw new IllegalArgumentException("Plugin '" + plugin.id() + "' has no built JAR at " + path + ". Build the repository with mvn install first.");
        return path;
    }

    public Path pipelineCoreJar() {
        Path path = pluginRoot.getParent().resolve("pipeline-core/target/pipeline-core.jar").normalize();
        if (!Files.isRegularFile(path)) throw new IllegalArgumentException("The pipeline-core JAR is missing at " + path + ". Build the repository with mvn install first.");
        return path;
    }

    public void verifyExecutable(PluginInfo info) {
        try (PluginLoader loader = new PluginLoader()) {
            Plugin instance = loader.load(pluginJar(info));
            if (instance instanceof ManagedPlugin managed) managed.close();
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Plugin '" + info.id() + "' is not executable: " + e.getMessage(), e);
        }
    }
    private List<PluginInfo> discover() {
        if (!Files.isDirectory(pluginRoot)) return List.of();
        List<PluginInfo> result = new ArrayList<>();
        try (var dirs = Files.list(pluginRoot)) {
            for (Path dir : dirs.filter(Files::isDirectory).sorted().toList()) {
                Path manifest = dir.resolve("src/main/resources/META-INF/PluginManifest.yaml");
                if (!Files.isRegularFile(manifest)) continue;
                try (InputStream in = Files.newInputStream(manifest)) {
                    Map<String, Object> root = map(new Yaml(new SafeConstructor(new LoaderOptions())).load(in));
                    Map<String, Object> p = map(root.get("plugin"));
                    Map<String, Object> input = map(p.get("input"));
                    Map<String, Object> output = map(p.get("output"));
                    String artifact = readArtifactId(dir.resolve("pom.xml"));
                    String id = str(p, "id");
                    String entrypoint = str(p, "entrypoint");
                    if (!id.matches("[A-Za-z0-9][A-Za-z0-9._-]*")) throw new IllegalArgumentException("Plugin ID contains unsupported characters: " + id);
                    if (!entrypoint.matches("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)+")) throw new IllegalArgumentException("Invalid Java entrypoint name: " + entrypoint);
                    if (!artifact.matches("[A-Za-z0-9][A-Za-z0-9._-]*")) throw new IllegalArgumentException("Invalid Maven artifactId: " + artifact);
                    Path normalizedDir = dir.toAbsolutePath().normalize();
                    if (!normalizedDir.startsWith(pluginRoot.toAbsolutePath().normalize())) throw new IllegalArgumentException("Plugin directory escapes configured plugin root.");
                    if (pluginDirectories.putIfAbsent(id, normalizedDir) != null)
                        throw new IllegalArgumentException("Plugin ID is declared by more than one source module: " + id);
                    result.add(new PluginInfo(id, str(p,"name"), str(p,"version"), str(p,"apiVersion"),
                            entrypoint, artifact, str(input,"type"), str(output,"type"),
                            Objects.toString(p.getOrDefault("description", "")), strings(p.get("capabilities")),
                            configurationFor(p,id)));
                } catch (Exception e) {
                    throw new IllegalStateException("Could not read plugin metadata from " + manifest + ": " + e.getMessage(), e);
                }
            }
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Could not scan plugin directory " + pluginRoot, e);
        }
        result.sort(Comparator.comparing(PluginInfo::id));
        return List.copyOf(result);
    }

    private static List<ConfigOption> configFor(String id) {
        return switch (id) {
            case "pdf-ocr-to-text" -> List.of(opt("PDF_OCR_DPI","300",false,false,"PDF rendering resolution (72-600)."), opt("PDF_OCR_TESSERACT_PATH","",false,false,"Optional Tesseract executable path."), opt("PDF_OCR_TESSDATA_PATH","",false,false,"Optional Tesseract data directory."), opt("PDF_OCR_LANGUAGE","eng",false,false,"Tesseract OCR language."), opt("PLUGIN_PDF_OCR_TIMEOUT_SECONDS","120",false,false,"OCR timeout in seconds."));
            case "static-splitter" -> List.of(opt("PLUGIN_STATIC_SPLITTER_CHUNK_SIZE","80",false,false,"Maximum characters per chunk."));
            case "document-splitter" -> List.of(opt("PLUGIN_DOCUMENT_SPLITTER_SEGMENT_SIZE","1000",false,false,"Maximum characters per chunk."), opt("PLUGIN_DOCUMENT_SPLITTER_SEGMENT_OVERLAP","100",false,false,"Overlapping characters."));
            case "citation-text-splitter" -> List.of(opt("PLUGIN_TEXT_SPLITTER_CHUNK_SIZE","1000",false,false,"Chunk character limit."), opt("PLUGIN_TEXT_SPLITTER_OVERLAP","120",false,false,"Overlapping characters."));
            case "ollama-embedding" -> List.of(opt("OLLAMA_BASE_URL","http://localhost:11434",true,false,"Ollama server URL."), opt("OLLAMA_EMBEDDING_MODEL","nomic-embed-text",true,false,"Embedding model name."), opt("OLLAMA_TIMEOUT_SECONDS","60",false,false,"Request timeout."));
            case "ollama-answer" -> List.of(opt("OLLAMA_BASE_URL","http://localhost:11434",true,false,"Ollama server URL."), opt("OLLAMA_CHAT_MODEL","llama3.1",true,false,"Chat model name."), opt("OLLAMA_TIMEOUT_SECONDS","60",false,false,"Request timeout."));
            case "neo4j-writer", "neo4j-retrieval" -> List.of(opt("NEO4J_URI","bolt://localhost:7687",true,false,"Neo4j Bolt URI."), opt("NEO4J_USERNAME","neo4j",true,false,"Neo4j username."), opt("NEO4J_PASSWORD","",true,true,"Set this secret locally after downloading."), opt("NEO4J_DATABASE","neo4j",false,false,"Neo4j database."), opt("NEO4J_VECTOR_INDEX","document_embedding_idx",false,false,"Neo4j vector index name."), opt("NEO4J_VECTOR_DIMENSIONS","768",false,false,"Embedding vector dimensions."), opt("OLLAMA_BASE_URL","http://localhost:11434",true,false,"Ollama server URL."), opt("OLLAMA_EMBEDDING_MODEL","nomic-embed-text",true,false,"Embedding model name."), opt("RAG_SEARCH_INCLUDE_CITATIONS","true",false,false,"Include source citations in answers."), opt("RAG_SEARCH_TOP_K","5",false,false,"Maximum retrieved chunks (1-100)."));
            default -> List.of();
        };
    }
    private static ConfigOption opt(String n,String d,boolean r,boolean s,String h){return new ConfigOption(n,typeOf(n),d,r,s,h);}
    private static String typeOf(String name){if(name.endsWith("_DPI")||name.endsWith("_SECONDS")||name.endsWith("_SIZE")||name.endsWith("_OVERLAP")||name.endsWith("_DIMENSIONS")||name.endsWith("_TOP_K"))return "integer";if(name.equals("RAG_SEARCH_INCLUDE_CITATIONS"))return "boolean";return "string";}
    private static Object defaultValue(ConfigOption option){try{return switch(option.type()){case "integer"->Integer.parseInt(option.defaultValue());case "number"->Double.parseDouble(option.defaultValue());case "boolean"->Boolean.parseBoolean(option.defaultValue());default->option.defaultValue();};}catch(NumberFormatException e){return option.defaultValue();}}
    private static List<ConfigOption> configurationFor(Map<String,Object> plugin,String id){
        Object raw=plugin.get("configuration");
        if(raw instanceof Map<?,?> config&&config.get("properties") instanceof Map<?,?> properties){
            Set<String> requiredNames=new HashSet<>();if(config.get("required") instanceof List<?> names)names.forEach(name->requiredNames.add(String.valueOf(name)));
            List<ConfigOption> result=new ArrayList<>();
            for(var entry:properties.entrySet())if(entry.getKey()!=null&&entry.getValue() instanceof Map<?,?> field){
                String name=entry.getKey().toString();String type=Objects.toString(field.get("type"),"string");String def=Objects.toString(field.get("default"),"");
                boolean required=requiredNames.contains(name)||Boolean.parseBoolean(Objects.toString(field.get("required"),"false"));
                result.add(new ConfigOption(name,type,def,required,Boolean.parseBoolean(Objects.toString(field.get("sensitive"),"false")),Objects.toString(field.get("description"),""),integerValue(field.get("minimum")),integerValue(field.get("maximum"))));
            }
            return List.copyOf(result);
        }
        return configFor(id);
    }
    private static Integer integerValue(Object value){if(value instanceof Number n)return n.intValue();if(value==null)return null;try{return Integer.valueOf(value.toString());}catch(NumberFormatException e){return null;}}
    private static Path locateRoot(String configured) {
        if (configured != null && !configured.isBlank()) return Path.of(configured).toAbsolutePath().normalize();
        Path cwd = Path.of("").toAbsolutePath();
        for (Path p : List.of(cwd.resolve("plugins"), cwd.resolve("../plugins"), cwd.resolve("../plugin-pipeline/plugins")))
            if (Files.isDirectory(p)) return p.normalize();
        return cwd.resolve("plugins");
    }
    private static String readArtifactId(Path pom) throws Exception {
        var factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        var doc = factory.newDocumentBuilder().parse(pom.toFile());
        var nodes = doc.getDocumentElement().getElementsByTagName("artifactId");
        // first project-level artifactId is before any dependency/parent element
        for (int i=0;i<nodes.getLength();i++) {
            var n=nodes.item(i); if (n.getParentNode()==doc.getDocumentElement()) return n.getTextContent().trim();
        }
        throw new IllegalArgumentException("pom.xml has no project artifactId");
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> map(Object o){if(!(o instanceof Map<?,?> m))throw new IllegalArgumentException("Expected a YAML mapping");return (Map<String,Object>)m;}
    private static String str(Map<String,Object> m,String k){Object v=m.get(k);if(v==null||v.toString().isBlank())throw new IllegalArgumentException("Missing manifest field '"+k+"'");return v.toString();}
    private static List<String> strings(Object o){if(!(o instanceof List<?> l))return List.of();return l.stream().map(String::valueOf).toList();}
}
