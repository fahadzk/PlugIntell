package com.core.docintel.text2cypher;

import com.core.docintel.neo4j.GraphPolicy;
import com.core.docintel.pipeline.api.*;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SemanticRetrievalRegressionTest {
    static final String QUESTION="I'm traveling from Rawalpindi to Karachi on Tezgam. Which stations will I pass through between Lahore and Multan, in the correct order, and why might this train be a good choice for my family?";
    static final List<String> STATIONS=List.of("Rawalpindi","Chaklala","Gujar Khan","Jhelum","Lala Musa Jn.","Gujrat","Wazirabad Jn","Gujranwala","Lahore Jn.","Raiwind Jn.","Pattoki","Okara","Sahiwal","Chichawatni","Mian Channu","Khanewal Jn","Multan Cantt","Bahawalpur","Khanpur Jn.","Rahim Yar Khan","Ghotki","Rohri Jn.","Khairpur","Mehrabpur","Nawabshah Jn.","Shahdadpur","Tando Adam","Hyderabad Jn","Landhi Jn.","Drigh Road","Karachi Cantt");
    static final List<String> INTERMEDIATE=STATIONS.subList(9,16);
    static Map<String,Object> part(String id,String kind,boolean supported,List<String> columns,List<String> evidence){return Map.of("id",id,"request",kind.equals("DESCRIPTION")?"Why is this a good choice for my family?":"Requested graph information","kind",kind,"supported",supported,"resultColumns",columns,"schemaEvidence",evidence,"reason",supported?"":"No descriptive attributes are present in the graph schema.");}
    static Map<String,Object> anchor(String role,String mention,String parameter){return Map.of("role",role,"mention",mention,"label",role.equals("SUBJECT")?"Train":"Station","property","name","parameter",parameter);}
    static JsonNode route(boolean multi,boolean reverse,boolean descriptionSupported){
        String start=reverse?"Multan":"Lahore",end=reverse?"Lahore":"Multan";
        var intent=new LinkedHashMap<String,Object>();intent.put("operation","PATH");intent.put("ordered",true);intent.put("intermediateOnly",true);intent.put("direction",reverse?"IN":"OUT");
        intent.put("subrouteStart",start);intent.put("subrouteEnd",end);intent.put("journeyStart",reverse?"Karachi":"Rawalpindi");intent.put("journeyEnd",reverse?"Rawalpindi":"Karachi");intent.put("pathColumn","path");
        intent.put("parts",List.of(part("route","PATH",true,List.of("path"),List.of("Station.name","NEXT_STOP")),part("family","DESCRIPTION",descriptionSupported,descriptionSupported?List.of("description"):List.of(),descriptionSupported?List.of("Train.description"):List.of())));
        intent.put("anchors",List.of(anchor("SUBROUTE_START",start,"start"),anchor("SUBROUTE_END",end,"end"),anchor("SUBJECT","Tezgam","train")));
        String pattern=reverse?"(a)<-[:NEXT_STOP"+(multi?"*1..64":"")+"]-(b)":"(a)-[:NEXT_STOP"+(multi?"*1..64":"")+"]->(b)";
        return GraphPolicy.JSON.valueToTree(Map.of("cypher","MATCH (t:Train {name:$train})-[:STOPS_AT]->(a:Station {name:$start}), (t)-[:STOPS_AT]->(b:Station {name:$end}) MATCH p="+pattern+" RETURN p AS path"+(descriptionSupported?", t.description AS description":"")+" LIMIT 100","parameters",List.of(Map.of("name","train","value","Tezgam"),Map.of("name","start","value",start),Map.of("name","end","value",end)),"intent",intent));
    }
    static final class RailwayGraph implements GraphAccess {
        int reads,lookups;boolean empty,descriptions,unverifiedList,fullJourney;CypherGuard.Plan last;Map<String,Object> schema;
        RailwayGraph(){schema=new LinkedHashMap<>(Map.of("nodes",List.of(Map.of("labels",List.of("Train"),"properties",Map.of("name","STRING")),Map.of("labels",List.of("Station"),"properties",Map.of("name","STRING"))),"relationships",List.of(Map.of("type","NEXT_STOP","source","Station","target","Station"),Map.of("type","STOPS_AT","source","Train","target","Station","properties",Map.of("sequence","INTEGER"))),"constraints",List.of(Map.of("type","UNIQUENESS","labelsOrTypes",List.of("Station"),"properties",List.of("name")))));}
        public void verify(){}
        public Map<String,Object> schema(int limit){return schema;}
        public Rows query(CypherGuard.Plan plan,int maxRows,int maxBytes){
            if(plan.cypher().contains("elementId(n) AS elementId")){
                lookups++;String mention=plan.parameters().get("mention").toString();List<Map<String,Object>> rows=new ArrayList<>();
                if(mention.equals("Tezgam"))rows.add(Map.of("elementId","train","value","Tezgam"));
                for(int i=0;i<STATIONS.size();i++)if(STATIONS.get(i).toLowerCase(Locale.ROOT).contains(mention.toLowerCase(Locale.ROOT)))rows.add(Map.of("elementId","s"+i,"value",STATIONS.get(i)));
                return new Rows(rows,List.of(),List.of(),false);
            }
            reads++;last=plan;if(empty)return new Rows(List.of(Map.of("path",List.of())),List.of(),List.of(),false);
            if(unverifiedList)return new Rows(List.of(Map.of("stations",INTERMEDIATE.stream().sorted().toList())),List.of(),List.of(),false);
            if(plan.cypher().contains("RETURN t.description AS description"))return new Rows(List.of(Map.of("description","Recorded descriptive information from the graph")),List.of(),List.of(),false);
            assertTrue(plan.cypher().contains("*1..64"),"A single-hop query must be rejected before reading");
            assertEquals("Tezgam",plan.parameters().get("train"));
            int a=STATIONS.indexOf(plan.parameters().get("start")),b=STATIONS.indexOf(plan.parameters().get("end"));assertTrue(a>=0&&b>=0,"Names must resolve to canonical stored values");
            if(fullJourney){a=0;b=STATIONS.size()-1;}
            List<Map<String,Object>> nodes=new ArrayList<>(),edges=new ArrayList<>();int step=a<b?1:-1;
            for(int i=a;;i+=step){nodes.add(node(i));if(i==b)break;int left=Math.min(i,i+step),right=Math.max(i,i+step);edges.add(Map.of("id","r:"+left,"source","n:s"+left,"target","n:s"+right,"label","NEXT_STOP"));}
            Map<String,Object> row=new LinkedHashMap<>();row.put("path",Map.of("nodes",nodes,"relationships",edges));if(descriptions)row.put("description",null);
            return new Rows(List.of(row),nodes,edges,false,List.of(new NativePath(0,"path",nodes,edges)));
        }
        static Map<String,Object> node(int i){return Map.of("id","n:s"+i,"label",STATIONS.get(i),"labels",List.of("Station"),"properties",Map.of("name",STATIONS.get(i)));}
        public void close(){}
    }
    static Neo4JText2CypherRetrievalPlugin plugin(GraphAccess graph,java.util.function.BiFunction<String,String,JsonNode> generate,List<PluginMessage> events,int retries){
        var model=new Neo4JText2CypherRetrievalPlugin.Model(){public void verify(){}public JsonNode generate(String system,String data){return generate.apply(system,data);}public void close(){}};
        var plugin=new Neo4JText2CypherRetrievalPlugin(events::add,graph,model);var config=new HashMap<>(Text2CypherPluginTest.config());config.put("KG_REPAIR_RETRIES",retries);config.put("T2C_LOG_CYPHER",true);plugin.configure(config);return plugin;
    }
    @Test void tezgamAcceptanceRepairsSingleHopResolvesSubrouteAndPreservesSevenOrderedIntermediateStations(){
        var graph=new RailwayGraph();var events=new ArrayList<PluginMessage>();int[] calls={0};
        try(var plugin=plugin(graph,(system,data)->{
            var input=GraphPolicy.parse(data);assertTrue(input.path("schema").has("constraints"));
            if(++calls[0]==1)return route(false,false,false);
            assertTrue(input.path("feedback").path("issues").toString().contains("SINGLE_HOP_INTERPRETATION"));return route(true,false,false);
        },events,2)){
            var result=(RetrievalResult)plugin.execute(new SearchRequest(QUESTION,false));
            assertEquals(INTERMEDIATE,entities(result));assertEquals(7,result.chunks().size());assertEquals("PARTIAL",result.graph().get("semanticStatus"));assertEquals(2,calls[0]);assertEquals(1,graph.reads);
            var path=(Map<?,?>)((List<?>)result.graph().get("paths")).getFirst();assertEquals("Lahore Jn.",path.get("start"));assertEquals("Multan Cantt",path.get("end"));assertEquals(8,path.get("hopCount"));
            assertEquals(1,((List<?>)result.graph().get("unansweredParts")).size());assertTrue(result.graph().get("unansweredParts").toString().toLowerCase(Locale.ROOT).contains("family"));assertTrue(result.chunks().getFirst().text().contains("unansweredParts"));
            assertEquals("Lahore Jn.",graph.last.parameters().get("start"));assertEquals("Multan Cantt",graph.last.parameters().get("end"));assertFalse(events.toString().contains("fixture-only"));
        }
    }
    @Test void reverseDirectionReturnsReversedIntermediatesAndKeepsPhysicalDirection(){
        var graph=new RailwayGraph();try(var plugin=plugin(graph,(s,d)->route(true,true,false),new ArrayList<>(),2)){
            var result=(RetrievalResult)plugin.execute(new SearchRequest("Travel from Karachi to Rawalpindi; which intermediate nodes are between Multan and Lahore, in order?",false));
            var reverse=new ArrayList<>(INTERMEDIATE);Collections.reverse(reverse);assertEquals(reverse,entities(result));
            assertEquals("IN",((Map<?,?>)((List<?>)result.graph().get("paths")).getFirst()).get("direction"));
        }
    }
    @Test void clipsAFullJourneyPathToTheRequestedSubrouteWithoutAlphabeticSorting(){
        var graph=new RailwayGraph();graph.fullJourney=true;try(var plugin=plugin(graph,(s,d)->route(true,false,false),new ArrayList<>(),2)){
            var result=(RetrievalResult)plugin.execute(new SearchRequest(QUESTION,false));assertEquals(INTERMEDIATE,entities(result));assertFalse(entities(result).contains("Rawalpindi"));assertFalse(entities(result).contains("Lahore Jn."));assertFalse(entities(result).contains("Multan Cantt"));assertFalse(entities(result).contains("Karachi Cantt"));
        }
    }
    @Test void atMostTwoRepairsPreserveTheRouteWhenDescriptionRemainsMissing(){
        var graph=new RailwayGraph();graph.descriptions=true;graph.schema.put("nodes",List.of(Map.of("labels",List.of("Train"),"properties",Map.of("name","STRING","description","STRING")),Map.of("labels",List.of("Station"),"properties",Map.of("name","STRING"))));
        int[] calls={0};try(var plugin=plugin(graph,(s,d)->{calls[0]++;if(calls[0]>1)assertTrue(GraphPolicy.parse(d).path("feedback").path("issues").toString().contains("MISSING_PART_RESULTS"));return route(true,false,true);},new ArrayList<>(),3)){
            var result=(RetrievalResult)plugin.execute(new SearchRequest(QUESTION,false));assertEquals(3,calls[0]);assertEquals(INTERMEDIATE,entities(result));assertEquals("PARTIAL",result.graph().get("semanticStatus"));assertEquals(true,result.graph().get("correctionBudgetExhausted"));
        }
    }
    @Test void emptyReadsTriggerStructuredFeedbackAndDoNotInventAPath(){
        var graph=new RailwayGraph();graph.empty=true;int[] calls={0};try(var plugin=plugin(graph,(s,d)->{calls[0]++;if(calls[0]>1)assertTrue(GraphPolicy.parse(d).path("feedback").path("issues").toString().contains("NO_VERIFIED_PATH"));return route(true,false,false);},new ArrayList<>(),2)){
            var result=(RetrievalResult)plugin.execute(new SearchRequest(QUESTION,false));assertEquals(3,calls[0]);assertTrue(result.chunks().isEmpty());assertEquals("EMPTY",result.graph().get("semanticStatus"));assertTrue(((List<?>)result.graph().get("paths")).isEmpty());
        }
    }
    @Test void aLaterComplementaryReadMergesWithPreviouslyVerifiedRouteInsteadOfReplacingIt(){
        var graph=new RailwayGraph();graph.descriptions=true;graph.schema.put("nodes",List.of(Map.of("labels",List.of("Train"),"properties",Map.of("name","STRING","description","STRING")),Map.of("labels",List.of("Station"),"properties",Map.of("name","STRING"))));
        int[] calls={0};try(var plugin=plugin(graph,(s,d)->{
            if(++calls[0]==1)return route(true,false,true);
            var root=(com.fasterxml.jackson.databind.node.ObjectNode)route(true,false,true).deepCopy();var intent=(com.fasterxml.jackson.databind.node.ObjectNode)root.path("intent");
            intent.put("operation","LOOKUP");intent.put("pathColumn","");intent.set("parts",GraphPolicy.JSON.valueToTree(List.of(part("route","PATH",false,List.of(),List.of()),part("family","DESCRIPTION",true,List.of("description"),List.of("Train.description")))));
            root.put("cypher","MATCH (t:Train {name:$train}) RETURN t.description AS description");root.set("parameters",GraphPolicy.JSON.valueToTree(List.of(Map.of("name","train","value","Tezgam"))));return root;
        },new ArrayList<>(),2)){
            var result=(RetrievalResult)plugin.execute(new SearchRequest(QUESTION,false));assertEquals(2,calls[0]);assertEquals("COMPLETE",result.graph().get("semanticStatus"));
            var rows=(List<Map<String,Object>>)result.graph().get("rows");assertEquals(INTERMEDIATE,rows.stream().filter(r->r.containsKey("entity")).map(r->r.get("entity").toString()).toList());assertTrue(rows.stream().anyMatch(r->r.containsKey("description")));assertTrue(((List<?>)result.graph().get("unansweredParts")).isEmpty());
        }
    }
    @Test void overallEndpointsCannotReplaceTheBetweenEndpoints(){
        var wrong=route(true,false,false).deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)wrong.path("intent")).put("subrouteStart","Rawalpindi").put("subrouteEnd","Karachi");
        var draft=SemanticPlan.parse(wrong,SemanticPlan.hints(QUESTION));assertTrue(SemanticEvaluation.validate(draft,SemanticPlan.hints(QUESTION),64).stream().anyMatch(i->i.code().equals("SUBROUTE_ENDPOINT_MISMATCH")));
    }
    static List<String> entities(RetrievalResult result){return ((List<Map<String,Object>>)result.graph().get("rows")).stream().map(row->row.get("entity").toString()).toList();}
}
