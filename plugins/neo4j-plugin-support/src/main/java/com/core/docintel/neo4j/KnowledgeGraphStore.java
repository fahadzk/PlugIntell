package com.core.docintel.neo4j;
import com.core.docintel.pipeline.api.*;
import com.core.docintel.pipeline.api.KnowledgeGraph.*;
import java.time.Duration;
import java.util.*;

/** Shared repository seam for deterministic tests; the production implementation is Neo4jChunkRepository. */
public interface KnowledgeGraphStore extends AutoCloseable {
    record Schema(Set<String> entityTypes,Set<String> relationshipTypes,boolean truncated) {
        public Schema {entityTypes=Set.copyOf(entityTypes);relationshipTypes=Set.copyOf(relationshipTypes);}
    }
    record Plan(List<String> entities,List<String> aliases,String semanticText,Set<String> entityTypes,
                Set<String> relationshipTypes,String direction,int hops) {
        public Plan {entities=List.copyOf(entities);aliases=List.copyOf(aliases);entityTypes=Set.copyOf(entityTypes);relationshipTypes=Set.copyOf(relationshipTypes);}
    }
    record Limits(int seeds,int results,int nodes,int relationships,int paths,int chunks,int degree,int contextChars,
                  Duration timeout,String fulltextIndex,int fusionK) {
        public Limits {
            if(seeds<1||seeds>100||results<1||results>100||nodes<1||nodes>1000||relationships<1||relationships>2000
                ||paths<1||paths>1000||chunks<1||chunks>100||degree<1||degree>100||contextChars<256||contextChars>200000
                ||timeout==null||timeout.isNegative()||timeout.isZero()||timeout.toSeconds()>120||fusionK<1||fusionK>1000)
                throw new IllegalArgumentException("Invalid graph retrieval limits.");
            approvedIndex(fulltextIndex);
        }
    }
    static String approvedIndex(String name){
        String approved=System.getenv().getOrDefault("PLUGINTELL_KG_FULLTEXT_INDEX","plugintell_kg_entities");
        if(name==null||!name.matches("[A-Za-z][A-Za-z0-9_]{0,63}")||!name.equals(approved))
            throw new IllegalArgumentException("KG_FULLTEXT_INDEX must match the host-approved PLUGINTELL_KG_FULLTEXT_INDEX (default plugintell_kg_entities).");
        return name;
    }
    WriteStatistics replace(Extraction extraction,PluginExecutionContext scope,Duration timeout);
    Schema schema(PluginExecutionContext scope,int scanLimit,Duration timeout);
    RetrievalResult retrieve(SearchRequest request,Plan plan,PluginExecutionContext scope,Limits limits,List<String> warnings);
    void graphPreflight(boolean provision,String index,Duration timeout);
    @Override void close();
}
