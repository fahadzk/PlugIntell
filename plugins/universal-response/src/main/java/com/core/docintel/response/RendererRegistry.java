package com.core.docintel.response;
import java.util.*;
import static com.core.docintel.response.ResponseModel.*;

/** Explicit registration; each handler validates and transforms only its own data contract. */
public final class RendererRegistry {
    public interface Handler {String type();Object transform(Object data);}
    private final Map<String,Handler> handlers=new LinkedHashMap<>();
    public RendererRegistry register(Handler handler){if(handlers.putIfAbsent(handler.type(),handler)!=null)throw new IllegalArgumentException("Duplicate renderer.");return this;}
    public Block transform(String type,Object data,ResponseConfig config){if(!config.renderers().contains(type))throw new IllegalArgumentException("Renderer is disabled.");var handler=handlers.get(type);if(handler==null)throw new IllegalArgumentException("Unknown renderer.");return block(type,handler.transform(data));}
    private record SimpleHandler(String type,java.util.function.Function<Object,Object> converter) implements Handler{public Object transform(Object data){return converter.apply(data);}}
    public static RendererRegistry standard(){
        var registry=new RendererRegistry();
        for(String text:List.of("markdown","text","code","mermaid","xml"))registry.register(new SimpleHandler(text,data->{if(data instanceof Map<?,?> map)return Map.of("content",ResponseJson.text(map.get("content")),"language",ResponseJson.text(map.get("language")));return Map.of("content",ResponseJson.text(data),"language",text.equals("xml")?"xml":"");}));
        registry.register(new SimpleHandler("json",data->ResponseJson.parse(new String(ResponseJson.bytes(data),java.nio.charset.StandardCharsets.UTF_8))));
        registry.register(new SimpleHandler("html",data->Map.of("content",SafeContent.html(content(data)))));
        registry.register(new SimpleHandler("svg",data->Map.of("content",SafeContent.svg(content(data)))));
        registry.register(new SimpleHandler("table",RendererRegistry::table));registry.register(new SimpleHandler("chart",RendererRegistry::chart));registry.register(new SimpleHandler("graph",RendererRegistry::graph));
        registry.register(new SimpleHandler("media",data->{var map=ResponseJson.object(data);String url=ResponseJson.text(map.get("url")),kind=ResponseJson.text(map.getOrDefault("kind","image"));if(!Set.of("image","audio","video").contains(kind)||!SafeContent.mediaUrl(url))throw new IllegalArgumentException("Media requires a safe HTTPS reference.");return Map.of("url",url,"kind",kind,"title",ResponseJson.text(map.get("title")));}));
        registry.register(new SimpleHandler("widget",RendererRegistry::widget));return registry;
    }
    private static String content(Object data){return data instanceof Map<?,?> map?ResponseJson.text(map.get("content")):ResponseJson.text(data);}
    static Map<String,Object> table(Object data){
        Object raw=data instanceof Map<?,?> map?map.get("rows"):data;if(!(raw instanceof List<?> rows)||rows.isEmpty()||rows.size()>5000)throw new IllegalArgumentException("Table requires 1–5000 rows.");
        LinkedHashSet<String> columns=new LinkedHashSet<>();List<Map<String,Object>> normalized=new ArrayList<>();
        for(Object row:rows){Map<String,Object> values=ResponseJson.object(row);columns.addAll(values.keySet());if(columns.size()>100)throw new IllegalArgumentException("Table has too many columns.");normalized.add(values);}
        return Map.of("columns",List.copyOf(columns),"rows",normalized);
    }
    static Map<String,Object> chart(Object data){
        List<?> rows;
        if(data instanceof Map<?,?> map&&map.get("labels") instanceof List<?> labels&&map.get("datasets") instanceof List<?> datasets){
            if(labels.isEmpty()||labels.size()>5000||datasets.isEmpty()||datasets.size()>20)throw new IllegalArgumentException("Chart size is unsupported.");
            List<Map<String,Object>> converted=new ArrayList<>();for(int i=0;i<labels.size();i++){Map<String,Object> row=new LinkedHashMap<>();row.put("label",ResponseJson.text(labels.get(i)));for(Object item:datasets){var series=ResponseJson.object(item);if(!(series.get("data") instanceof List<?> values)||values.size()!=labels.size())throw new IllegalArgumentException("Chart series length mismatch.");row.put(ResponseJson.text(series.getOrDefault("label","value")),values.get(i));}converted.add(row);}rows=converted;
        }else rows=(List<?>)table(data).get("rows");
        LinkedHashSet<String> numeric=new LinkedHashSet<>();for(Object item:rows){var row=ResponseJson.object(item);row.forEach((key,value)->{if(value instanceof Number n&&Double.isFinite(n.doubleValue()))numeric.add(key);});}
        numeric.removeIf(key->rows.stream().anyMatch(item->!(ResponseJson.object(item).get(key) instanceof Number n)||!Double.isFinite(n.doubleValue())));
        if(numeric.isEmpty()||numeric.size()>20)throw new IllegalArgumentException("Chart needs finite numeric columns.");
        var first=ResponseJson.object(rows.getFirst());String label=first.keySet().stream().filter(key->!numeric.contains(key)).findFirst().orElse("");return Map.of("rows",rows,"series",List.copyOf(numeric),"label",label);
    }
    static Map<String,Object> graph(Object data){
        var map=ResponseJson.object(data);Object nr=map.containsKey("nodes")?map.get("nodes"):map.get("entities"),er=map.containsKey("edges")?map.get("edges"):map.getOrDefault("relationships",map.getOrDefault("facts",List.of()));
        if(!(nr instanceof List<?> nodes)||!(er instanceof List<?> edges)||nodes.size()>500||edges.size()>2000)throw new IllegalArgumentException("Graph requires at most 500 nodes and 2000 edges.");
        List<Map<String,Object>> ns=new ArrayList<>(),es=new ArrayList<>();Set<String> ids=new HashSet<>();
        for(Object item:nodes){var node=ResponseJson.object(item);String id=ResponseJson.text(node.getOrDefault("id",node.get("key")));if(id.isBlank()||!ids.add(id))throw new IllegalArgumentException("Graph node IDs must be unique.");ns.add(Map.of("id",id,"label",ResponseJson.text(node.getOrDefault("label",node.getOrDefault("name",id))),"type",ResponseJson.text(node.get("type")),"order",ns.size()));}
        for(Object item:edges){var edge=ResponseJson.object(item);String source=ResponseJson.text(edge.getOrDefault("source",edge.get("source_id"))),target=ResponseJson.text(edge.getOrDefault("target",edge.get("target_id")));if(!ids.contains(source)||!ids.contains(target))throw new IllegalArgumentException("Graph edge endpoint is missing.");es.add(Map.of("id",ResponseJson.text(edge.getOrDefault("id","edge-"+es.size())),"source",source,"target",target,"label",ResponseJson.text(edge.getOrDefault("label",edge.get("type"))),"order",es.size()));}
        return Map.of("nodes",ns,"edges",es);
    }
    static Map<String,Object> widget(Object data){
        var spec=ResponseJson.object(data);if(!(spec.get("fields") instanceof List<?> fields)||fields.size()>20)throw new IllegalArgumentException("Widget needs up to 20 declared fields.");
        List<Map<String,Object>> normalized=new ArrayList<>();Set<String> ids=new HashSet<>();
        for(Object item:fields){var field=ResponseJson.object(item);String id=ResponseJson.text(field.get("id")),type=ResponseJson.text(field.getOrDefault("type","text"));if(!id.matches("[A-Za-z][A-Za-z0-9_]{0,39}")||!ids.add(id)||!Set.of("text","number","select","checkbox").contains(type))throw new IllegalArgumentException("Unsupported widget field.");List<String> options=field.get("options") instanceof List<?> list?list.stream().limit(100).map(ResponseJson::text).toList():List.of();normalized.add(Map.of("id",id,"type",type,"label",ResponseJson.text(field.getOrDefault("label",id)),"value",ResponseJson.text(field.get("value")),"options",options));}
        List<Map<String,Object>> actions=new ArrayList<>();if(spec.get("actions") instanceof List<?> list){if(list.size()>10)throw new IllegalArgumentException("Too many widget actions.");for(Object item:list){var action=ResponseJson.object(item);String operation=ResponseJson.text(action.get("action"));if(!Set.of("sum","subtract","multiply","divide","reset","filter","count").contains(operation))throw new IllegalArgumentException("Unsupported widget action.");List<String> inputs=action.get("inputs") instanceof List<?> a?a.stream().map(ResponseJson::text).toList():List.of();if(!ids.containsAll(inputs))throw new IllegalArgumentException("Unknown widget input.");actions.add(Map.of("action",operation,"label",ResponseJson.text(action.getOrDefault("label",operation)),"inputs",inputs));}}
        Object rows=spec.get("rows") instanceof List<?> list?table(list):Map.of();return Map.of("title",ResponseJson.text(spec.getOrDefault("title","Interactive tool")),"fields",normalized,"actions",actions,"table",rows);
    }
}
