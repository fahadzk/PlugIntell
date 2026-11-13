package com.core.docintel.response;
import com.core.docintel.pipeline.api.*;
import java.time.Instant;
import java.util.*;
import java.util.regex.*;
import static com.core.docintel.response.ResponseModel.*;

/** No model calls. Detects explicit blocks, metadata, content and modest format hints in that order. */
public final class ResponseDetector {
    private final RendererRegistry registry;
    public ResponseDetector(RendererRegistry registry){this.registry=registry;}
    public Response normalize(Object input,ResponseConfig config){
        String query,answer="";Map<String,Object> graph=Map.of();List<Source> sources=new ArrayList<>();
        if(input instanceof AnswerResult a){query=a.query();answer=a.answer();if(a.citationsEnabled())a.citations().stream().limit(100).forEach(c->sources.add(new Source(c.id(),c.sourceName(),c.pageNumber(),c.score())));}
        else if(input instanceof RetrievalResult r){query=r.query();graph=r.graph();if(r.citationsEnabled())r.chunks().stream().limit(100).forEach(c->sources.add(new Source(c.id(),c.sourceName(),c.pageNumber(),c.score())));}
        else throw new IllegalArgumentException("Universal Response expects AnswerResult or RetrievalResult.");
        List<Block> blocks=new ArrayList<>();List<String> warnings=new ArrayList<>();String preference=config.mode();
        if(input instanceof RetrievalResult){
            warnings.add("Showing retrieved data only; no generated answer was supplied. Add an answer plugin before this renderer for a prose response.");
            if(graph.get("warnings") instanceof List<?> messages)for(Object message:messages.stream().limit(20).toList())if(message instanceof String text&&!text.isBlank())warnings.add(text.substring(0,Math.min(1000,text.length())));
        }
        if(preference.equals("auto")){var hint=Pattern.compile("(?i)(?:as (?:a |an )?|\\[view:)(chart|table|graph|json|xml|code|mermaid|pdf|docx|xlsx|pptx)(?:\\b|\\])").matcher(query);if(hint.find())preference=hint.group(1).toLowerCase(Locale.ROOT);}
        boolean oversized=answer.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>config.maxResponseBytes()||!graph.isEmpty()&&ResponseJson.bytes(graph).length>config.maxResponseBytes();
        if(oversized){warnings.add("Response exceeds the configured display limit. The original pipeline result is preserved.");blocks.add(block("text","Response is too large for this viewer."));}
        else if(!answer.isEmpty()){
            var fenced=Pattern.compile("(?ms)```([A-Za-z0-9_+-]*)[^\\S\\n]*\\n(.*?)```").matcher(answer);int end=0;boolean found=false;
            while(fenced.find()&&blocks.size()<30){found=true;if(fenced.start()>end&&!answer.substring(end,fenced.start()).isBlank())add("markdown",answer.substring(end,fenced.start()),blocks,warnings,config);String language=fenced.group(1).toLowerCase(Locale.ROOT);String content=fenced.group(2);
                if(language.equals("json"))detect(content,preference,blocks,warnings,config);
                else if(Set.of("mermaid","svg","html","xml").contains(language))add(language,content,blocks,warnings,config);
                else add("code",Map.of("content",content,"language",language),blocks,warnings,config);end=fenced.end();
            }
            if(found){if(end<answer.length())add("markdown",answer.substring(end),blocks,warnings,config);}else detect(answer,preference,blocks,warnings,config);
        }else if(!graph.isEmpty()){
            if(graph.get("presentation") instanceof Map<?,?> presentation)structured(ResponseJson.object(presentation),preference,blocks,warnings,config);
            else if(graph.get("blocks") instanceof List<?>)structured(graph,preference,blocks,warnings,config);
            else if(graph.containsKey("nodes")||graph.containsKey("entities")){
                try{add("graph",RendererRegistry.graph(graph),blocks,warnings,config);}catch(IllegalArgumentException invalid){warnings.add("Retrieved graph could not be normalized. Private retrieval context was omitted.");add("text","Graph metadata is unsupported; source references remain available.",blocks,warnings,config);}
            }
            else add("json",Map.of("strategy",graph.getOrDefault("strategy","retrieval"),"sourceCount",sources.size()),blocks,warnings,config);
        }else add("markdown","Retrieved source references are available below. No generated answer or graph was supplied.",blocks,warnings,config);
        String requested=preference;
        if(!requested.equals("auto")&&!blocks.stream().anyMatch(b->b.type().equals(requested)||b.options().get("views") instanceof List<?> views&&views.contains(requested)))warnings.add("Requested "+preference+" output is not available from this response. Compatible content is shown instead; no data was invented.");
        if(blocks.isEmpty())blocks.add(block("text","No renderable response blocks were supplied."));
        return new Response("1.0",id(),Instant.now().toString(),query.length()>4096?query.substring(0,4096):query,warnings.isEmpty()?"complete":"warning",List.copyOf(blocks),List.copyOf(sources),Map.of("inputType",input.getClass().getSimpleName(),"theme",config.theme(),"allowRemoteMedia",config.remoteMedia()),List.copyOf(warnings),List.of());
    }
    private void detect(String raw,String preference,List<Block> blocks,List<String> warnings,ResponseConfig config){
        String text=raw.strip();if(text.isEmpty())return;
        if(text.startsWith("{")||text.startsWith("["))try{structured(ResponseJson.parse(text),preference,blocks,warnings,config);return;}catch(IllegalArgumentException ignored){warnings.add("Content resembled JSON but could not be parsed; displaying it as text.");}
        if(!preference.equals("auto")&&Set.of("code","text","markdown").contains(preference)){add(preference,text,blocks,warnings,config);return;}
        if(text.matches("(?s)^(?:<\\?xml.*?\\?>\\s*)?<svg[\\s>].*")){add("svg",text,blocks,warnings,config);return;}
        if(text.matches("(?is)^<(?:!doctype html|html|body|div|p|h[1-6]|table)[\\s>].*")){add("html",text,blocks,warnings,config);return;}
        if(text.startsWith("<?xml")||text.matches("(?s)^<[A-Za-z_][^>]*>.*</[^>]+>\\s*$")){add("xml",text,blocks,warnings,config);return;}
        if(text.matches("(?s)^(?:graph |flowchart |sequenceDiagram\\b).*")){add("mermaid",text,blocks,warnings,config);return;}
        if(text.matches("(?s)^(?:package [\\w.]+;|(?:public )?(?:final )?class |def \\w+\\(|import java\\.|SELECT .*FROM ).*")){add("code",Map.of("content",text,"language",text.startsWith("def ")?"python":text.startsWith("SELECT")?"sql":"java"),blocks,warnings,config);return;}
        add("markdown",text,blocks,warnings,config);
    }
    private void structured(Object data,String preference,List<Block> blocks,List<String> warnings,ResponseConfig config){
        if(data instanceof Map<?,?> raw){var map=ResponseJson.object(raw);
            if(map.get("blocks") instanceof List<?> list){if(list.size()>32)warnings.add("Only the first 32 blocks are displayed.");for(Object item:list.stream().limit(32).toList()){
                if(!(item instanceof Map<?,?>)){warnings.add("Skipped invalid block.");continue;}var entry=ResponseJson.object(item);String type=ResponseJson.text(entry.getOrDefault("type",entry.getOrDefault("renderer","text")));add(type,entry.getOrDefault("data",entry.getOrDefault("content","")),blocks,warnings,config);
            }return;}
            if(map.containsKey("nodes")||map.containsKey("entities")){add("graph",map,blocks,warnings,config);return;}
            if(map.containsKey("rows")){add(Set.of("chart","json").contains(preference)?preference:"table",map.get("rows"),blocks,warnings,config);return;}
            if(map.containsKey("labels")&&map.containsKey("datasets")){add("chart",map,blocks,warnings,config);return;}
            if(map.containsKey("fields")){add("widget",map,blocks,warnings,config);return;}
            if(map.containsKey("url")&&map.containsKey("kind")){add("media",map,blocks,warnings,config);return;}
        }
        if(data instanceof List<?> list&&!list.isEmpty()&&list.getFirst() instanceof Map<?,?>){add(Set.of("chart","json").contains(preference)?preference:"table",list,blocks,warnings,config);return;}
        add("json",data,blocks,warnings,config);
    }
    private void add(String type,Object data,List<Block> blocks,List<String> warnings,ResponseConfig config){
        if(blocks.size()>=32){if(!warnings.contains("Block limit reached."))warnings.add("Block limit reached.");return;}
        try{var result=registry.transform(type,data,config);List<String> views=new ArrayList<>(List.of(type));
            if(Set.of("table","chart","json").contains(type)){
                try{RendererRegistry.table(data);if(config.renderers().contains("table")&&!views.contains("table"))views.add("table");}catch(Exception ignored){}
                try{RendererRegistry.chart(data);if(config.renderers().contains("chart")&&!views.contains("chart"))views.add("chart");}catch(Exception ignored){}
            }
            if(!type.equals("json")&&Set.of("table","chart","graph","widget").contains(type)&&config.renderers().contains("json"))views.add("json");
            if(type.equals("graph")&&config.renderers().contains("table"))views.add("table");
            blocks.add(new Block(result.id(),result.type(),result.renderer(),result.data(),Map.of("views",List.copyOf(views))));
        }catch(Exception e){warnings.add("The "+type+" block could not be rendered safely or is disabled. A text fallback is shown.");String fallback=data instanceof String s?s:ResponseJson.pretty(data);if(fallback.length()>16000)fallback=fallback.substring(0,16000)+"\n[Display fallback truncated]";blocks.add(block("text",Map.of("content",fallback)));}
    }
}
