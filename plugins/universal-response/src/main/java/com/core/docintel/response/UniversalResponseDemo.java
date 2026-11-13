package com.core.docintel.response;
import com.core.docintel.pipeline.api.*;
import java.util.*;

/** Runnable end-to-end demo in the plugin JAR itself. No model, database or external frontend required. */
public final class UniversalResponseDemo {
    private UniversalResponseDemo(){}
    public static Map<String,Object> route(){
        List<String> stations=List.of("Rawalpindi","Jhelum","Lala Musa","Lahore","Multan","Bahawalpur","Rohri","Hyderabad","Karachi");
        List<Map<String,Object>> nodes=new ArrayList<>(),edges=new ArrayList<>();
        for(int i=0;i<stations.size();i++){nodes.add(Map.of("id","station-"+i,"name",stations.get(i),"type","Station","order",i));if(i>0)edges.add(Map.of("id","segment-"+i,"source","station-"+(i-1),"target","station-"+i,"type","NEXT_STATION"));}
        return Map.of("nodes",nodes,"edges",edges);
    }
    private static Map<String,Object> block(String type,Object data){return Map.of("type",type,"data",data);}
    public static void main(String[] args){
        int port=8765;boolean browser=true;for(int i=0;i<args.length;i++){if(args[i].equals("--port")&&i+1<args.length)port=Integer.parseInt(args[++i]);else if(args[i].equals("--no-browser"))browser=false;else throw new IllegalArgumentException("Usage: UniversalResponseDemo [--port 8765] [--no-browser]");}
        var plugin=new UniversalResponsePlugin(message->System.out.println(message.code()+": "+message.message()));plugin.configure(Map.of("UR_PORT",port,"UR_AUTO_OPEN_BROWSER",browser));
        plugin.execute(new AnswerResult("Explain a local response renderer","## Your answer, made useful\n\nThis response is rendered **locally**.\n\n- No extra model calls\n- Original pipeline output preserved\n- Bounded, temporary storage",false,List.of()));
        var employees=List.of(Map.of("name","Alice","department","Engineering","years",4),Map.of("name","Bilal","department","Operations","years",7));
        plugin.execute(new AnswerResult("Show employees as JSON",ResponseJson.pretty(employees),false,List.of()));
        var revenue=List.of(Map.of("month","Jan","revenue",120,"cost",80),Map.of("month","Feb","revenue",170,"cost",95),Map.of("month","Mar","revenue",145,"cost",88),Map.of("month","Apr","revenue",210,"cost",110));
        plugin.execute(new AnswerResult("Show monthly revenue as a chart",ResponseJson.pretty(revenue),false,List.of()));
        plugin.execute(new RetrievalResult("Illustrative Rawalpindi-to-Karachi route",true,List.of(new RetrievedChunk("route-source","This context is intentionally not exposed by the viewer.","Illustrative railway dataset",1,1)),route()));
        plugin.execute(new AnswerResult("Java example","```java\npublic final class Route {\n    public static String destination() {\n        return \"Karachi\";\n    }\n}\n```",false,List.of()));
        plugin.execute(new AnswerResult("Unsupported block fallback",ResponseJson.pretty(Map.of("blocks",List.of(block("unrecognized-format",Map.of("note","Shown safely as text."))))),false,List.of()));
        List<Object> blocks=new ArrayList<>();
        blocks.add(block("markdown","## From one answer to many views\nThis is an **illustrative dataset**, not a timetable or current service recommendation. The directed route retains the supplied station order. Switch compatible data views, explore the graph, or download a file."));
        blocks.add(block("table",revenue));blocks.add(block("chart",revenue));blocks.add(block("graph",route()));
        blocks.add(block("code",Map.of("content","public String destination() { return \"Karachi\"; }","language","java")));
        blocks.add(block("mermaid","flowchart LR\nInput[Query] --> Retriever[Retrieve context]\nRetriever --> LLM[Generate answer]\nLLM --> View[Universal response]"));
        blocks.add(block("mermaid","sequenceDiagram\nparticipant User\nparticipant Pipeline\nparticipant Viewer\nUser->>Pipeline: Ask a question\nPipeline->>Viewer: Publish response\nViewer-->>User: Explore local result"));
        blocks.add(block("widget",Map.of("title","Trip cost calculator","fields",List.of(Map.of("id","fare","label","Fare","type","number","value","120"),Map.of("id","extras","label","Extras","type","number","value","30")),"actions",List.of(Map.of("label","Calculate total","action","sum","inputs",List.of("fare","extras")),Map.of("label","Reset","action","reset","inputs",List.of())))));
        blocks.add(block("widget",Map.of("title","Filter sample revenue",
            "fields",List.of(Map.of("id","month","label","Month","type","select","value","Feb","options",List.of("Jan","Feb","Mar","Apr")),Map.of("id","confirmed","label","Confirm selection","type","checkbox","value","true")),
            "rows",revenue,"actions",List.of(
                Map.of("label","Filter months","action","filter","inputs",List.of("month")),
                Map.of("label","Count confirmations","action","count","inputs",List.of("confirmed"))
            ))));
        blocks.add(block("xml","<?xml version=\"1.0\"?><route><from>Rawalpindi</from><to>Karachi</to></route>"));
        blocks.add(block("svg","<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 500 100\"><rect x=\"10\" y=\"10\" width=\"480\" height=\"80\" rx=\"12\" fill=\"#e8f3ed\"/><text x=\"250\" y=\"58\" text-anchor=\"middle\" fill=\"#35765f\" font-size=\"20\">Local, reusable response rendering</text><script>window.__pwned=true</script></svg>"));
        blocks.add(block("html","<h2>Sanitized HTML preview</h2><p>Markup remains readable; active content is removed.</p><img src=x onerror=\"window.__pwned=true\"><script>window.__pwned=true</script>"));
        blocks.add(block("media",Map.of("kind","image","url","https://example.com/sample.png","title","Remote image reference (not automatically fetched)")));
        plugin.execute(new AnswerResult("Rawalpindi → Karachi · a richer response",ResponseJson.pretty(Map.of("blocks",blocks)),true,List.of(new RetrievedChunk("demo-source","Private source context not published.","Illustrative railway dataset",1,1))));
        System.out.println("\nViewer: "+plugin.viewerUrl().substring(0,plugin.viewerUrl().indexOf("/responses/"))+"/");
        System.out.println("The plugin's embedded server remains available. Press Ctrl+C to stop it.");plugin.close();
    }
}
