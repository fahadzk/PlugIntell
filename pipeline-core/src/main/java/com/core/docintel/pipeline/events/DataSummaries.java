package com.core.docintel.pipeline.events;
import com.core.docintel.pipeline.api.*;
import java.util.*;
/** Metadata only: never document text, questions, answers, vectors or provider responses. */
public final class DataSummaries{
 private DataSummaries(){}
 public static Map<String,Object> of(Object value){
  if(value==null)return Map.of("type","null");
  Map<String,Object> result=new LinkedHashMap<>();result.put("type",value.getClass().getSimpleName());
  if(value instanceof java.io.File file)result.put("bytes",file.length());
  else if(value instanceof TextDocument d)result.put("pageCount",d.pages().size());
  else if(value instanceof ChunkCollection c)result.put("chunkCount",c.chunks().size());
  else if(value instanceof EmbeddingCollection c)result.put("chunkCount",c.embeddings().size());
  else if(value instanceof StoredResult s)result.put("storedCount",s.storedChunks());
  else if(value instanceof KnowledgeGraph.Result g){result.put("type","KnowledgeGraphResult");result.put("processedChunkCount",g.processedChunkIds().size());result.put("failedChunkCount",g.failures().size());}
  else if(value instanceof RetrievalResult r)result.put("retrievedCount",r.chunks().size());
  else if(value instanceof AnswerResult a)result.put("citationCount",a.citations().size());
  else if(value instanceof SearchRequest s){result.put("topK",s.topK());result.put("citations",s.includeCitations());}
  else if(value instanceof String s)result.put("characters",s.length());
  else if(value instanceof Collection<?> list)result.put("count",list.size());
  return Collections.unmodifiableMap(result);
 }
}

