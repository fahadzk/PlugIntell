package com.core.docintel.text2cypher;
import com.core.docintel.pipeline.api.*;
import com.core.docintel.neo4j.GraphPolicy;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class Text2CypherPluginTest {
    static Map<String,Object> config(){return Map.of("OLLAMA_CHAT_MODEL","fixture","NEO4J_PASSWORD","fixture-only","NEO4J_DATABASE","pakistan-railways","KG_REPAIR_RETRIES",1);}
    static final class Graph implements GraphAccess {
        int schemaCalls,queries;CypherGuard.Plan plan;
        List<Map<String,Object>> overrideRows;
        public void verify(){}
        public Map<String,Object> schema(int limit){schemaCalls++;return Map.of("labels",List.of("Train","Station"),"relationships",List.of("STOPS_AT","NEXT_STOP"));}
        public Rows query(CypherGuard.Plan plan,int maxRows,int maxBytes){this.plan=plan;queries++;List<Map<String,Object>> rows=new ArrayList<>();for(int i=1;i<=31;i++)rows.add(Map.of("sequence",i,"station","Station "+i));return new Rows(overrideRows==null?rows:overrideRows,List.of(),List.of(),false);}
        public void close(){}
    }
    @Test void retrievesExternalGraphCachesSchemaAndDoesNotTruncateRouteToTopK(){
        var graph=new Graph();var events=new ArrayList<PluginMessage>();var inputs=new ArrayList<String>();
        var model=new Neo4JText2CypherRetrievalPlugin.Model(){public void verify(){}public JsonNode generate(String system,String data){inputs.add(data);assertTrue(system.contains("ORDER BY"));return GraphPolicy.parse("{\"cypher\":\"MATCH (t:Train {name:$train})-[:STOPS_AT]->(s:Station) RETURN s.name AS station ORDER BY s.sequence LIMIT 100\",\"parameters\":[{\"name\":\"train\",\"value\":\"Tezgam\"}]}");}public void close(){}};
        var plugin=new Neo4JText2CypherRetrievalPlugin(events::add,graph,model);plugin.configure(config());plugin.preflight();
        var query=new SearchRequest("Which stations does Tezgam stop at?",false,5);var result=(RetrievalResult)plugin.execute(query);plugin.execute(query);
        assertEquals(31,result.chunks().size());assertFalse(result.citationsEnabled());assertEquals("TEXT_TO_CYPHER",result.graph().get("strategy"));assertEquals(1,graph.schemaCalls);assertEquals(2,graph.queries);
        assertEquals(Map.of("train","Tezgam"),graph.plan.parameters());assertTrue(inputs.getFirst().contains("Station"));assertFalse(inputs.getFirst().contains("fixture-only"));
        assertEquals(1,((Map<?,?>)((List<?>)result.graph().get("rows")).getFirst()).get("sequence"));assertTrue(result.chunks().getFirst().sourceName().contains("pakistan-railways"));
        assertTrue(events.stream().anyMatch(e->e.code().equals("text2cypher.retrieval.completed")));assertFalse(events.toString().contains("fixture-only"));
        assertTrue(result.graph().containsKey("presentation"));assertTrue(result.graph().get("warnings").toString().contains("not verified original-document citations"));plugin.close();
    }
    @Test void manualSchemaAvoidsMetadataProceduresAndUnsafeQueriesNeverExecute(){
        var graph=new Graph();var calls=new int[]{0};
        var model=new Neo4JText2CypherRetrievalPlugin.Model(){public void verify(){}public JsonNode generate(String system,String data){calls[0]++;return GraphPolicy.parse("{\"cypher\":\"MATCH(n) DELETE n RETURN n\",\"parameters\":[]}");}public void close(){}};
        var plugin=new Neo4JText2CypherRetrievalPlugin(PluginMessages.noop(),graph,model);var settings=new HashMap<>(config());settings.put("T2C_SCHEMA_JSON","{\"labels\":[\"Train\",\"Station\"]}");plugin.configure(settings);plugin.preflight();
        assertThrows(IllegalArgumentException.class,()->plugin.execute(new SearchRequest("Delete everything",false)));assertEquals(0,graph.queries);assertEquals(0,graph.schemaCalls);assertEquals(2,calls[0]);plugin.close();
    }
    @Test void validationRepairIsBoundedAndDoesNotSendPriorUnsafeOutputToModel(){
        var graph=new Graph();var calls=new int[]{0};
        var model=new Neo4JText2CypherRetrievalPlugin.Model(){public void verify(){}public JsonNode generate(String system,String data){assertFalse(system.contains("DELETE"));return ++calls[0]==1?GraphPolicy.parse("{\"cypher\":\"MATCH(n) DELETE n RETURN n\",\"parameters\":[]}"):GraphPolicy.parse("{\"cypher\":\"MATCH(n:Station) RETURN n.name LIMIT 100\",\"parameters\":[]}");}public void close(){}};
        var plugin=new Neo4JText2CypherRetrievalPlugin(PluginMessages.noop(),graph,model);plugin.configure(config());assertDoesNotThrow(()->plugin.execute(new SearchRequest("Stations",false)));assertEquals(2,calls[0]);assertEquals(1,graph.queries);plugin.close();
    }
    @Test void reportsEmptyAggregateRowsWithoutLosingValuesAndLogsTheSelectedConnection(){
        var graph=new Graph();var messages=new ArrayList<PluginMessage>();
        var model=new Neo4JText2CypherRetrievalPlugin.Model(){public void verify(){}public JsonNode generate(String system,String data){return GraphPolicy.parse("{\"cypher\":\"MATCH(n) RETURN collect(n.name) AS stations\",\"parameters\":[]}");}public void close(){}};
        var plugin=new Neo4JText2CypherRetrievalPlugin(messages::add,graph,model);var settings=new HashMap<>(config());settings.put("NEO4J_URI","neo4j://db.example:7687?routing=private-routing-value");
        plugin.configure(settings);plugin.preflight();
        var row=new LinkedHashMap<String,Object>();row.put("stations",List.of());row.put("description",null);graph.overrideRows=List.of(row);
        var empty=(RetrievalResult)plugin.execute(new SearchRequest("Stations",false));
        assertEquals("EMPTY_VALUES",empty.graph().get("dataStatus"));assertEquals(0L,empty.graph().get("populatedRowCount"));assertEquals(1,empty.chunks().size());assertTrue(empty.graph().get("warnings").toString().contains("every value is null"));
        assertTrue(messages.stream().anyMatch(m->m.message().contains("Connecting to Neo4j")&&m.message().contains("neo4j://db.example:7687")&&m.message().contains("pakistan-railways")));
        assertTrue(messages.stream().anyMatch(m->m.message().contains("Connected to Neo4j")));assertFalse(messages.toString().contains("private-routing-value"));assertFalse(messages.toString().contains("fixture-only"));
        graph.overrideRows=List.of(Map.of("count",0,"connected",false));var zero=(RetrievalResult)plugin.execute(new SearchRequest("Count",false));assertEquals("DATA",zero.graph().get("dataStatus"));assertEquals(1L,zero.graph().get("populatedRowCount"));
        graph.overrideRows=List.of();var missing=(RetrievalResult)plugin.execute(new SearchRequest("Missing",false));assertEquals("NO_ROWS",missing.graph().get("dataStatus"));plugin.close();
    }
}
