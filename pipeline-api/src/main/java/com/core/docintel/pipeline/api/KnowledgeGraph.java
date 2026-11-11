package com.core.docintel.pipeline.api;

import java.util.List;
import java.util.Map;

/** Evidence offsets use Java UTF-16 indices, end exclusive, in the original chunk text. */
public final class KnowledgeGraph {
    private KnowledgeGraph() {}
    public record Evidence(String quote,int start,int end) {}
    public record Entity(String localId,String key,String type,String name,List<String> aliases,
                         Map<String,Object> properties,Evidence evidence) {
        public Entity { aliases=List.copyOf(aliases);properties=Map.copyOf(properties); }
    }
    public record Fact(String key,String source,String type,String target,Map<String,Object> properties,Evidence evidence) {
        public Fact { properties=Map.copyOf(properties); }
    }
    public record Extraction(TextChunk chunk,List<Entity> entities,List<Fact> facts,Map<String,Object> provenance) {
        public Extraction { entities=List.copyOf(entities);facts=List.copyOf(facts);provenance=Map.copyOf(provenance); }
    }
    public record WriteStatistics(int entitiesObserved,int factsObserved,long entitiesCreated,long factsCreated,
                                  long evidenceCreated,long graphRevision,boolean unchanged) {}
    public record ChunkFailure(String chunkId,String code) {}
    public record Result(List<Extraction> extractions,List<String> processedChunkIds,List<WriteStatistics> writes,
                         List<String> warnings,List<ChunkFailure> failures,Map<String,Object> provenance) {
        public Result { extractions=List.copyOf(extractions);processedChunkIds=List.copyOf(processedChunkIds);
            writes=List.copyOf(writes);warnings=List.copyOf(warnings);failures=List.copyOf(failures);provenance=Map.copyOf(provenance); }
        @Override public String toString(){return "KnowledgeGraphResult[processedChunks="+processedChunkIds.size()+", failures="+failures.size()+"]";}
    }
}
