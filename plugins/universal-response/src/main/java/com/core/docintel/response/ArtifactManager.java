package com.core.docintel.response;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static com.core.docintel.response.ResponseModel.*;

public final class ArtifactManager {
    public record File(String id,String name,String mime,byte[] bytes){public Artifact descriptor(){return new Artifact(id,name,mime,bytes.length);}}
    /** Extension point: exporters return actual bytes; unsupported formats never get placeholder files. */
    public interface Exporter {List<File> export(Response response);}
    private final List<Exporter> exporters=new ArrayList<>();
    public ArtifactManager register(Exporter exporter){exporters.add(exporter);return this;}
    public static ArtifactManager standard(){return new ArtifactManager().register(ArtifactManager::basic);}
    private static final Set<String> MIME=Set.of("application/json","text/csv","text/html","image/svg+xml","application/pdf","application/vnd.openxmlformats-officedocument.wordprocessingml.document","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet","application/vnd.openxmlformats-officedocument.presentationml.presentation","image/png","image/jpeg","image/webp","audio/mpeg","audio/wav","video/mp4","video/webm");
    public List<File> create(Response response){List<File> result=new ArrayList<>();long size=0;for(var exporter:exporters){List<File> files;try{files=exporter.export(response);}catch(RuntimeException failedExport){continue;}for(var file:files){if(!MIME.contains(file.mime())||!file.name().matches("[A-Za-z0-9_.-]{1,100}")||!file.id().matches("[a-f0-9-]{36}"))continue;if(result.size()>=12||size+file.bytes().length>8388608)break;result.add(file);size+=file.bytes().length;}}return List.copyOf(result);}
    private static File file(String name,String mime,String text){return new File(id(),name,mime,text.getBytes(StandardCharsets.UTF_8));}
    private static List<File> basic(Response response){
        List<File> result=new ArrayList<>();result.add(file("response.json","application/json",ResponseJson.pretty(response)));
        StringBuilder html=new StringBuilder("<!doctype html><html><head><meta charset=\"utf-8\"><title>PlugIntell response</title></head><body><h1>").append(SafeContent.escape(response.query())).append("</h1>");
        int index=0;for(var block:response.blocks()){
            index++;String content=block.data() instanceof Map<?,?> map&&map.containsKey("content")?ResponseJson.text(map.get("content")):ResponseJson.pretty(block.data());html.append("<section><h2>").append(SafeContent.escape(block.type())).append("</h2><pre>").append(SafeContent.escape(content)).append("</pre></section>");
            if(Set.of("table","chart","json").contains(block.type()))try{var table=RendererRegistry.table(block.data());result.add(file("table-"+index+".csv","text/csv",csv(table)));}catch(Exception ignored){}
            if(block.type().equals("svg"))result.add(file("visual-"+index+".svg","image/svg+xml",SafeContent.svg(content)));
            if(block.type().equals("graph"))result.add(file("graph-"+index+".svg","image/svg+xml",graphSvg(ResponseJson.object(block.data()))));
        }
        html.append("</body></html>");result.add(file("response.html","text/html",html.toString()));return result;
    }
    static String csv(Map<String,Object> table){
        var columns=(List<?>)table.get("columns");var rows=(List<?>)table.get("rows");StringBuilder out=new StringBuilder();out.append(String.join(",",columns.stream().map(ArtifactManager::cell).toList())).append("\r\n");
        for(Object item:rows){var row=ResponseJson.object(item);out.append(String.join(",",columns.stream().map(key->cell(row.get(key.toString()))).toList())).append("\r\n");}return out.toString();
    }
    private static String cell(Object value){String text=value==null?"":value instanceof Map<?,?>||value instanceof Collection<?>?ResponseJson.pretty(value):value.toString();if(value instanceof String&&(text.startsWith("\t")||text.startsWith("\r")||text.startsWith("\n")||text.stripLeading().matches("(?s)^[=+@\\-].*")))text="'"+text;return "\""+text.replace("\"","\"\"")+"\"";}
    private static String graphSvg(Map<String,Object> graph){
        var nodes=(List<?>)graph.get("nodes");var edges=(List<?>)graph.get("edges");int width=Math.max(500,nodes.size()*160);Map<String,Integer> positions=new HashMap<>();
        StringBuilder svg=new StringBuilder("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 "+width+" 240\"><defs><marker id=\"arrow\" markerWidth=\"10\" markerHeight=\"10\" refX=\"8\" refY=\"3\" orient=\"auto\"><path d=\"M0,0 L0,6 L9,3 z\" fill=\"#5486da\"/></marker></defs>");
        for(int i=0;i<nodes.size();i++)positions.put(ResponseJson.text(ResponseJson.object(nodes.get(i)).get("id")),80+i*160);
        for(Object item:edges){var e=ResponseJson.object(item);int x1=positions.get(e.get("source")),x2=positions.get(e.get("target"));svg.append("<line x1=\"").append(x1+35).append("\" y1=\"110\" x2=\"").append(x2-40).append("\" y2=\"110\" stroke=\"#5486da\" marker-end=\"url(#arrow)\"/>");}
        for(Object item:nodes){var n=ResponseJson.object(item);int x=positions.get(n.get("id"));svg.append("<circle cx=\"").append(x).append("\" cy=\"110\" r=\"35\" fill=\"#dceaff\" stroke=\"#5486da\"/><text x=\"").append(x).append("\" y=\"170\" text-anchor=\"middle\" font-size=\"12\">").append(SafeContent.escape(ResponseJson.text(n.get("label")))).append("</text>");}
        return SafeContent.svg(svg.append("</svg>").toString());
    }
}
