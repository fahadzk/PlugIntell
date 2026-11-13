package com.core.docintel.response;
import com.core.docintel.pipeline.api.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ResponseDetectorTest {
    @Test void rawDatabaseResultsShowEmptyValueWarningsAndExplainTheMissingAnswerStep(){
        var row=new LinkedHashMap<String,Object>();row.put("stations",List.of());row.put("description",null);
        var input=new RetrievalResult("Stations",false,List.of(),Map.of("warnings",List.of("The query returned only empty values."),"presentation",Map.of("blocks",List.of(Map.of("type","table","data",List.of(row))))));
        var result=new ResponseDetector(RendererRegistry.standard()).normalize(input,ResponseConfig.from(Map.of("UR_UI_ENABLED",false)));
        assertEquals("warning",result.status());assertTrue(result.warnings().stream().anyMatch(w->w.contains("no generated answer")));assertTrue(result.warnings().contains("The query returned only empty values."));
        assertEquals("table",result.blocks().getFirst().type());assertTrue(ResponseJson.pretty(result).contains("\"description\" : null"));
    }
    final ResponseConfig config=ResponseConfig.from(Map.of("UR_UI_ENABLED",false,"UR_AUTO_OPEN_BROWSER",false));
    ResponseModel.Response normalize(String answer){return new ResponseDetector(RendererRegistry.standard()).normalize(new AnswerResult("A query",answer,false,List.of()),config);}
    @Test void markdownAndCodeUseSafeContentAndPreserveOriginalResult(){
        var plugin=new UniversalResponsePlugin();plugin.configure(Map.of("UR_UI_ENABLED",false));var original=new AnswerResult("Question","## Heading\n\n**Answer**",true,List.of(new RetrievedChunk("c","PRIVATE_CONTEXT","source.pdf",1,1)));
        assertSame(original,plugin.execute(original));assertNull(plugin.viewerUrl());assertEquals("markdown",plugin.lastResponse().blocks().getFirst().type());assertFalse(ResponseJson.pretty(plugin.lastResponse()).contains("PRIVATE_CONTEXT"));
        var code=normalize("```java\npublic class Hello {}\n```");assertEquals("code",code.blocks().getFirst().type());assertEquals("java",((Map<?,?>)code.blocks().getFirst().data()).get("language"));
    }
    @Test void employeeJsonOffersTableJsonAndChartWithoutInventingData(){
        var result=normalize("[{\"name\":\"Alice\",\"years\":4},{\"name\":\"Bilal\",\"years\":7}]");var block=result.blocks().getFirst();assertEquals("table",block.type());assertTrue(((List<?>)block.options().get("views")).containsAll(List.of("table","json","chart")));
        var chart=new ResponseDetector(RendererRegistry.standard()).normalize(new AnswerResult("Show as a chart","[{\"month\":\"Jan\",\"revenue\":100}]",false,List.of()),config);assertEquals("chart",chart.blocks().getFirst().type());
        var unavailable=new ResponseDetector(RendererRegistry.standard()).normalize(new AnswerResult("Show as a graph","No structured nodes were returned.",false,List.of()),config);assertEquals("markdown",unavailable.blocks().getFirst().type());assertFalse(unavailable.warnings().isEmpty());
    }
    @Test void graphRagPreservesStationOrderDirectionAndHidesEvidenceText(){
        var graph=new HashMap<>(UniversalResponseDemo.route());graph.put("evidence",List.of(Map.of("quote","SECRET_EVIDENCE")));
        var input=new RetrievalResult("Rawalpindi to Karachi",true,List.of(new RetrievedChunk("source","PRIVATE_CONTEXT","route.csv",1,1)),graph);var result=new ResponseDetector(RendererRegistry.standard()).normalize(input,config);
        var data=(Map<?,?>)result.blocks().getFirst().data();var nodes=(List<?>)data.get("nodes");var edges=(List<?>)data.get("edges");assertEquals("Rawalpindi",((Map<?,?>)nodes.getFirst()).get("label"));assertEquals("Karachi",((Map<?,?>)nodes.getLast()).get("label"));assertEquals("station-0",((Map<?,?>)edges.getFirst()).get("source"));assertEquals("station-1",((Map<?,?>)edges.getFirst()).get("target"));assertFalse(ResponseJson.pretty(result).contains("SECRET_EVIDENCE"));assertFalse(ResponseJson.pretty(result).contains("PRIVATE_CONTEXT"));
        var disabled=new ResponseDetector(RendererRegistry.standard()).normalize(input,ResponseConfig.from(Map.of("UR_RENDERERS","markdown")));assertFalse(ResponseJson.pretty(disabled).contains("SECRET_EVIDENCE"));assertFalse(ResponseJson.pretty(disabled).contains("PRIVATE_CONTEXT"));
    }
    @Test void multipleIndependentBlocksSurviveOneFailedRenderer(){
        var response=normalize("{\"blocks\":[{\"type\":\"markdown\",\"data\":\"Explanation\"},{\"type\":\"alien\",\"data\":\"unknown\"},{\"type\":\"table\",\"data\":[{\"x\":1}]}]}");assertEquals(List.of("markdown","text","table"),response.blocks().stream().map(ResponseModel.Block::type).toList());assertFalse(response.warnings().isEmpty());
        var mixed=normalize("Explanation\n```json\n[{\"x\":1}]\n```\n```mermaid\nflowchart LR\nA-->B\n```");assertEquals(List.of("markdown","table","mermaid"),mixed.blocks().stream().map(ResponseModel.Block::type).toList());
    }
    @Test void htmlSvgAndWidgetsCannotCarryExecutableCode(){
        String html=SafeContent.html("<h1 onclick='evil()'>Safe</h1><script>alert(1)</script><img src=x onerror='evil()'><iframe src='file:///secret'></iframe>");assertTrue(html.contains("Safe"));assertFalse(html.contains("script"));assertFalse(html.contains("onclick"));assertFalse(html.contains("onerror"));assertFalse(html.contains("file:"));
        String svg=SafeContent.svg("<svg xmlns='http://www.w3.org/2000/svg' onload='evil()'><script>alert(1)</script><foreignObject><div>bad</div></foreignObject><rect width='20' fill='url(https://evil.example/x)'/><image href='file:///secret'/></svg>");assertFalse(svg.contains("script"));assertFalse(svg.contains("onload"));assertFalse(svg.contains("foreignObject"));assertFalse(svg.contains("https:"));assertFalse(svg.contains("file:"));
        assertThrows(IllegalArgumentException.class,()->SafeContent.svg("<!DOCTYPE svg [<!ENTITY x SYSTEM 'file:///secret'>]><svg>&x;</svg>"));
        assertThrows(IllegalArgumentException.class,()->RendererRegistry.widget(Map.of("fields",List.of(),"actions",List.of(Map.of("action","eval","inputs",List.of())))));
        assertFalse(SafeContent.mediaUrl("javascript:alert(1)"));assertFalse(SafeContent.mediaUrl("https://localhost/private"));
        assertEquals("text",new ResponseDetector(RendererRegistry.standard()).normalize(new AnswerResult("q","hello",false,List.of()),ResponseConfig.from(Map.of("UR_RENDERERS","json"))).blocks().getFirst().type());
    }
    @Test void oversizedAndUnsupportedDocumentRequestsProduceHonestFallback(){
        var small=ResponseConfig.from(Map.of("UR_UI_ENABLED",false,"UR_MAX_RESPONSE_BYTES",1024));var original=new AnswerResult("q","large".repeat(1000),false,List.of());var plugin=new UniversalResponsePlugin();plugin.configure(Map.of("UR_UI_ENABLED",false,"UR_MAX_RESPONSE_BYTES",1024));assertSame(original,plugin.execute(original));assertFalse(plugin.lastResponse().warnings().isEmpty());
        var pdf=new ResponseDetector(RendererRegistry.standard()).normalize(new AnswerResult("q","Plain answer",false,List.of()),ResponseConfig.from(Map.of("UR_OUTPUT_MODE","pdf","UR_UI_ENABLED",false)));assertTrue(pdf.warnings().stream().anyMatch(w->w.contains("pdf")));assertTrue(pdf.artifacts().isEmpty());
        assertThrows(IllegalArgumentException.class,()->ResponseConfig.from(Map.of("UR_HOST","0.0.0.0")));
    }
}
