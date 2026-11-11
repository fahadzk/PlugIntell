package com.core.docintel.neo4j;
import com.core.docintel.pipeline.api.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class GraphPolicyTest {
    static final PluginExecutionContext SCOPE=new PluginExecutionContext("test","private","run","pipeline",7,"extract");
    static final String TEXT="Aster rack R-7 powers Boreal rack R-8.";
    static TextChunk chunk(String id){return new TextChunk(id,"doc","manual.txt",2,id.equals("two")?1:0,TEXT);}
    static ObjectNode extraction(){return (ObjectNode)GraphPolicy.parse("""
      {"entities":[
       {"id":"a","type":"Component","name":"Aster","aliases":["Aster rack"],"properties":{"identifier":"R-7"},"evidence":{"quote":"Aster rack R-7","occurrence":0}},
       {"id":"b","type":"Component","name":"Boreal","aliases":[],"properties":{"identifier":"R-8"},"evidence":{"quote":"Boreal rack R-8","occurrence":0}}
      ],"relationships":[{"source":"a","type":"POWERS","target":"b","properties":{},"evidence":{"quote":"Aster rack R-7 powers Boreal rack R-8.","occurrence":0}}]}
      """);}
    static Map<String,Object> propertyConfig(){return Map.of("KG_IDENTITY_POLICY","PROPERTY","KG_IDENTITY_PROPERTY","identifier","KG_PROPERTY_SCHEMAS","{\"identifier\":{\"type\":\"string\",\"maxLength\":100}}");}
    @Test void validatesEvidencePropertiesAndStableScopedIdentities(){
        var policy=new GraphPolicy(propertyConfig());var first=policy.validate(extraction(),chunk("one"),SCOPE,Map.of());
        assertEquals(0,first.entities().getFirst().evidence().start());assertEquals(TEXT.length(),first.facts().getFirst().evidence().end());
        var second=policy.validate(extraction(),chunk("two"),SCOPE,Map.of());assertEquals(first.facts().getFirst().key(),second.facts().getFirst().key());
        var other=policy.validate(extraction(),chunk("one"),new PluginExecutionContext("other","private",null,null,null,null),Map.of());
        assertNotEquals(first.entities().getFirst().key(),other.entities().getFirst().key());
        assertEquals(first,policy.validate(extraction(),chunk("one"),SCOPE,Map.of()));
    }
    @Test void defaultIdentityNeverMergesNamesAcrossChunks(){
        var config=new HashMap<>(propertyConfig());config.put("KG_IDENTITY_POLICY","CHUNK_EVIDENCE");var policy=new GraphPolicy(config);
        assertNotEquals(policy.validate(extraction(),chunk("a"),SCOPE,Map.of()).entities().getFirst().key(),policy.validate(extraction(),chunk("b"),SCOPE,Map.of()).entities().getFirst().key());
    }
    @Test void emptyExtractionIsValid(){assertTrue(new GraphPolicy(Map.of()).validate(GraphPolicy.parse("{\"entities\":[],\"relationships\":[]}"),chunk("one"),SCOPE,Map.of()).entities().isEmpty());}
    @Test void rejectsUnsupportedEndpointsQuotesTypesAndProperties(){
        var policy=new GraphPolicy(propertyConfig());
        List<java.util.function.Consumer<ObjectNode>> corruptions=List.of(
            r->r.put("namespace","attacker"),
            r->((ObjectNode)r.path("relationships").get(0)).put("target","missing"),
            r->((ObjectNode)r.path("entities").get(0).path("evidence")).put("quote","Aster invented quote"),
            r->((ObjectNode)r.path("entities").get(0).path("evidence")).put("quote"," Aster rack R-7 "),
            r->((ObjectNode)r.path("entities").get(0).path("evidence")).put("occurrence",4294967296L),
            r->((ObjectNode)r.path("entities").get(0)).put("type","MATCH (x) DELETE x"),
            r->((ObjectNode)r.path("entities").get(0).path("properties")).put("identifier",42),
            r->((ObjectNode)r.path("entities").get(0).path("properties")).put("identifier","not in evidence"),
            r->((ObjectNode)r.path("entities").get(0).path("properties")).put("unauthorized",true),
            r->((ObjectNode)r.path("entities").get(1)).put("id","a")
        );
        for(var corruption:corruptions){var root=extraction();corruption.accept(root);assertThrows(IllegalArgumentException.class,()->policy.validate(root,chunk("one"),SCOPE,Map.of()));}
        var strict=new HashMap<>(propertyConfig());strict.put("KG_SCHEMA_MODE","STRICT");assertThrows(IllegalArgumentException.class,()->new GraphPolicy(strict));
        strict.put("KG_ENTITY_TYPES","Artifact,Site");strict.put("KG_RELATIONSHIP_TYPES","FOUND_AT");assertThrows(IllegalArgumentException.class,()->new GraphPolicy(strict).validate(extraction(),chunk("one"),SCOPE,Map.of()));
        strict.put("KG_SCHEMA_MODE","OPEN");strict.put("KG_ENTITY_TYPES","");strict.put("KG_RELATIONSHIP_TYPES","");strict.put("KG_RELATIONSHIP_CONSTRAINTS","[{\"source\":\"Component\",\"relationship\":\"LOCATED_AT\",\"target\":\"Site\"}]");assertThrows(IllegalArgumentException.class,()->new GraphPolicy(strict).validate(extraction(),chunk("one"),SCOPE,Map.of()));
    }
    @Test void propertyBoundsAndExtractionLimitsAreEnforced(){
        var c=new HashMap<>(propertyConfig());c.put("KG_MAX_ENTITIES",1);var limited=new GraphPolicy(c);assertThrows(IllegalArgumentException.class,()->limited.validate(extraction(),chunk("one"),SCOPE,Map.of()));
        c=new HashMap<>(propertyConfig());c.put("KG_PROPERTY_SCHEMAS","{\"identifier\":{\"type\":\"string\",\"enum\":[\"other\"]}}");var p=new GraphPolicy(c);assertThrows(IllegalArgumentException.class,()->p.validate(extraction(),chunk("one"),SCOPE,Map.of()));
        assertThrows(IllegalArgumentException.class,()->new GraphPolicy(Map.of("KG_PROPERTY_SCHEMAS","{\"id\":{\"type\":\"object\"}}")));
    }
    @Test void supportsAnotherDomainWithoutCodeChanges(){
        String text="Codex artifact A-1 was recovered at Delta site S-2.";
        String json=GraphPolicy.encode(extraction()).replace("Component","Artifact").replace("Aster rack R-7","Codex artifact A-1").replace("Boreal rack R-8","Delta site S-2").replace("Aster","Codex").replace("Boreal","Delta").replace("R-7","A-1").replace("R-8","S-2").replace("POWERS","RECOVERED_AT").replace("powers","was recovered at");
        var root=GraphPolicy.parse(json.replace("Codex rack","Codex artifact"));((ObjectNode)root.path("entities").get(1)).put("type","Site");
        var result=new GraphPolicy(propertyConfig()).validate(root,new TextChunk("a","doc","catalog.txt",1,0,text),SCOPE,Map.of());assertEquals("RECOVERED_AT",result.facts().getFirst().type());
    }
    @Test void shippedDomainExamplesValidateAgainstTheirActualSourceChunks()throws Exception{
        for(String domain:List.of("engineering","heritage")){
            Path base=Path.of("../../examples/knowledge-graph");var chunk=GraphPolicy.JSON.readValue(Files.readString(base.resolve(domain+"-chunk.json")),TextChunk.class);
            var extraction=GraphPolicy.parse(Files.readString(base.resolve(domain+"-extraction.json")));
            var result=new GraphPolicy(propertyConfig()).validate(extraction,chunk,SCOPE,Map.of());assertEquals(2,result.entities().size());assertEquals(1,result.facts().size());
        }
    }
}
