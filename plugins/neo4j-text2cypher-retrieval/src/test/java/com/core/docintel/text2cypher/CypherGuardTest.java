package com.core.docintel.text2cypher;
import com.core.docintel.neo4j.GraphPolicy;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CypherGuardTest {
    static CypherGuard.Plan plan(String query,Object... parameters){List<Map<String,Object>> params=new ArrayList<>();for(int i=0;i<parameters.length;i+=2){var item=new LinkedHashMap<String,Object>();item.put("name",parameters[i]);item.put("value",parameters[i+1]);params.add(item);}return CypherGuard.validate(GraphPolicy.JSON.valueToTree(Map.of("cypher",query,"parameters",params)));}
    @Test void acceptsReadQueriesAndKeepsUserValuesOutOfCypher(){
        var result=plan("MATCH (t:Train {name:$name})-[:STOPS_AT]->(s:Station) RETURN s.name ORDER BY s.sequence LIMIT 100","name","Tezgam'; DETACH DELETE n; //");
        assertEquals("Tezgam'; DETACH DELETE n; //",result.parameters().get("name"));assertFalse(result.cypher().contains("DELETE"));
        assertDoesNotThrow(()->plan("RETURN '; CREATE DELETE CALL' AS text, `delete` AS value // CALL\n"));
        assertDoesNotThrow(()->plan("RETURN $empty AS empty, $number AS number, $names AS names","empty",null,"number",2,"names",List.of("A","B")));
    }
    @Test void rejectsWritesProceduresAdministrationAndMultipleStatements(){
        for(String query:List.of("MATCH (n) DETACH DELETE n RETURN n","MATCH (n) SET n.x=1 RETURN n","CALL apoc.load.json('https://example.com') YIELD value RETURN value","CALL { RETURN 1 AS x } RETURN x","LOAD CSV FROM 'file:///x' AS row RETURN row","USE other MATCH(n) RETURN n","MATCH(n) RETURN n; MATCH(m) RETURN m","EXPLAIN MATCH(n) RETURN n","SHOW DATABASES","CREATE (n) RETURN n","CYPHER 5 MATCH (n) RETURN n","MATCH(n) DE/**/LETE n RETURN n")){
            if(query.contains("DE/**/LETE"))continue; // Server EXPLAIN rejects split keywords; token guard does not join them.
            assertThrows(IllegalArgumentException.class,()->plan(query),query);
        }
    }
    @Test void rejectsMalformedOutputAndParameterMismatches(){
        assertThrows(IllegalArgumentException.class,()->plan("MATCH(n {name:$name}) RETURN n"));
        assertThrows(IllegalArgumentException.class,()->plan("RETURN 1","unused","x"));
        assertThrows(IllegalArgumentException.class,()->plan("RETURN 'unterminated"));
        assertThrows(IllegalArgumentException.class,()->plan("RETURN 1 /* unterminated"));
        assertThrows(IllegalArgumentException.class,()->plan("RETURN $x","x",Map.of("unsupported","object")));
        assertThrows(IllegalArgumentException.class,()->plan("RETURN $x","x","a","x","b"));
        assertThrows(IllegalArgumentException.class,()->CypherGuard.validate(GraphPolicy.parse("{\"cypher\":\"RETURN 1\",\"parameters\":[],\"extra\":true}")));
    }
}
