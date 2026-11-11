package com.core.docintel.neo4j;

import com.core.docintel.pipeline.api.*;
import com.core.docintel.neo4j.KnowledgeGraphStore.*;
import java.util.*;
import java.util.concurrent.CancellationException;

/** Bounded application BFS, not a variable-length database path query. */
public final class GraphTraversal {
    public record Node(String key,String type,String name,List<String> aliases) {public Node{aliases=List.copyOf(aliases);}}
    public record Edge(String key,String source,String type,String target,Node sourceNode,Node targetNode) {}
    public record Support(String id,String subject,String kind,String chunkId,String documentId,String sourceName,int page,
                          String text,String quote,int start,int end,String properties,Map<String,Object> provenance) {}
    public record Neighbors(List<Edge> edges,int explored,boolean capped) {}
    public interface Reader {
        long revision();
        List<Node> exact(List<String> names,int limit);
        List<Node> lexical(String text,int limit);
        Neighbors neighbors(String key,String direction,int limit,Set<String> entityTypes,Set<String> relationTypes);
        List<Support> evidence(String subject,int limit);
        default boolean seedCapped(){return false;}
    }
    private record Path(List<String> nodes,List<String> relationships,double seedScore) {}
    private final Reader reader;private final Limits limits;
    public GraphTraversal(Reader reader,Limits limits){this.reader=reader;this.limits=limits;}
    public RetrievalResult search(SearchRequest request,Plan plan,List<String> initialWarnings) {
        long began=System.nanoTime(),revision=reader.revision();List<String> warnings=new ArrayList<>(initialWarnings);
        LinkedHashSet<String> terms=new LinkedHashSet<>(plan.entities());terms.addAll(plan.aliases());
        if(terms.isEmpty())terms.addAll(Arrays.stream(plan.semanticText().split("[^\\p{L}\\p{N}]+"))
                .filter(s->s.length()>1).limit(16).toList());
        List<Node> exact=reader.exact(new ArrayList<>(terms),limits.seeds()),lexical=reader.lexical(plan.semanticText(),limits.seeds());
        Map<String,Node> candidates=new LinkedHashMap<>();Map<String,Double> scores=new HashMap<>();
        for(List<Node> source:List.of(exact,lexical))for(int i=0;i<source.size();i++){
            Node n=source.get(i);if(!plan.entityTypes().isEmpty()&&!plan.entityTypes().contains(n.type()))continue;
            candidates.putIfAbsent(n.key(),n);scores.merge(n.key(),1.0/(limits.fusionK()+i+1),Double::sum);
        }
        List<Node> seeds=candidates.values().stream().sorted(Comparator.<Node>comparingDouble(n->-scores.get(n.key())).thenComparing(Node::key))
                .limit(Math.min(limits.seeds(),Math.min(limits.results(),request.topK()))).toList();
        boolean[] truncated={reader.seedCapped()||exact.size()>=limits.seeds()||lexical.size()>=limits.seeds()||candidates.size()>seeds.size()};
        Map<String,Node> nodes=new LinkedHashMap<>();Map<String,Edge> edges=new LinkedHashMap<>();
        Map<String,Double> nodeScores=new HashMap<>(),edgeScores=new HashMap<>();
        Map<String,Support> evidence=new LinkedHashMap<>();Map<String,RetrievedChunk> chunks=new LinkedHashMap<>();
        int[] budget={limits.contextChars()};ArrayDeque<Path> queue=new ArrayDeque<>();List<Map<String,Object>> paths=new ArrayList<>();
        for(Node n:seeds)if(addNode(n,scores.get(n.key()),nodes,nodeScores,evidence,chunks,budget,truncated))queue.add(new Path(List.of(n.key()),List.of(),scores.get(n.key())));
        Set<String> expanded=new HashSet<>();int explored=exact.size()+lexical.size(),exploredEdges=0;
        while(!queue.isEmpty()){
            cancelled();Path path=queue.remove();String current=path.nodes().getLast();
            if(path.relationships().size()>=plan.hops()||!expanded.add(current))continue;
            int remaining=limits.relationships()-exploredEdges;if(remaining<=0){truncated[0]=true;break;}
            Neighbors batch=reader.neighbors(current,plan.direction(),Math.min(limits.degree(),remaining),plan.entityTypes(),plan.relationshipTypes());
            explored+=batch.explored();exploredEdges+=batch.explored();truncated[0]|=batch.capped();
            for(Edge edge:batch.edges()){
                cancelled();if(edges.containsKey(edge.key()))continue;
                if(edges.size()>=limits.relationships()||paths.size()>=limits.paths()){truncated[0]=true;break;}
                Node next=edge.source().equals(current)?edge.targetNode():edge.sourceNode();
                if(path.nodes().contains(next.key()))continue;
                double score=path.seedScore()/(2+path.relationships().size());
                if(!addNode(next,score,nodes,nodeScores,evidence,chunks,budget,truncated))continue;
                int edgeCost=GraphPolicy.encode(List.of(edge.key(),edge.source(),edge.type(),edge.target())).length();
                if(edgeCost>budget[0]||!addSupports(edge.key(),score,evidence,chunks,budget,truncated)){truncated[0]=true;continue;}
                if(edgeCost>budget[0]){truncated[0]=true;continue;}budget[0]-=edgeCost;edges.put(edge.key(),edge);edgeScores.put(edge.key(),score);
                List<String> pn=new ArrayList<>(path.nodes());pn.add(next.key());List<String> pr=new ArrayList<>(path.relationships());pr.add(edge.key());
                int pathCost=GraphPolicy.encode(List.of(pn,pr)).length();if(pathCost>budget[0]){truncated[0]=true;continue;}budget[0]-=pathCost;
                paths.add(Map.of("nodes",List.copyOf(pn),"relationships",List.copyOf(pr),"score",score,"inferredDirectRelationship",false));
                queue.add(new Path(pn,pr,path.seedScore()));
            }
        }
        long endRevision=reader.revision();if(endRevision!=revision)warnings.add("Graph changed while reading; results are not a historical snapshot.");
        if(truncated[0])warnings.add("One or more candidate, degree, graph-size or context budgets were reached; results may be partial.");
        if(seeds.isEmpty())warnings.add("No supported entity candidates matched the question.");
        long ambiguous=seeds.stream().collect(java.util.stream.Collectors.groupingBy(n->n.name().toLowerCase(Locale.ROOT),java.util.stream.Collectors.counting())).values().stream().filter(n->n>1).count();
        if(ambiguous>0)warnings.add("Ambiguous entity candidates were retained as separate identities.");
        List<Map<String,Object>> nodeOutput=nodes.values().stream().sorted(Comparator.<Node>comparingDouble(n->-nodeScores.get(n.key())).thenComparing(Node::key)).map(n->Map.<String,Object>of("id",n.key(),"type",n.type(),"name",n.name(),"aliases",n.aliases(),"score",nodeScores.get(n.key()),"assertion","stored")).toList();
        List<Map<String,Object>> edgeOutput=edges.values().stream().sorted(Comparator.<Edge>comparingDouble(e->-edgeScores.get(e.key())).thenComparing(Edge::key)).map(e->Map.<String,Object>of("id",e.key(),"source",e.source(),"type",e.type(),"target",e.target(),"score",edgeScores.get(e.key()),"assertion","stored")).toList();
        paths.sort(Comparator.<Map<String,Object>>comparingDouble(p->-((Number)p.get("score")).doubleValue()).thenComparing(p->GraphPolicy.encode(p.get("nodes"))));
        List<Map<String,Object>> sourceOutput=evidence.values().stream().map(e->Map.<String,Object>of("id",e.id(),"subject",e.subject(),"kind",e.kind(),"chunkId",e.chunkId(),"documentId",e.documentId(),"quote",e.quote(),"start",e.start(),"end",e.end(),"propertiesJson",e.properties(),"provenance",e.provenance())).toList();
        Map<String,Object> diagnostics=new LinkedHashMap<>();diagnostics.put("warnings",List.copyOf(warnings));diagnostics.put("truncated",truncated[0]);diagnostics.put("exploredCandidates",explored);diagnostics.put("expandedNodes",expanded.size());diagnostics.put("ambiguousNames",ambiguous);diagnostics.put("contextCharactersUsed",limits.contextChars()-budget[0]);diagnostics.put("elapsedMillis",(System.nanoTime()-began)/1_000_000);diagnostics.put("revisionAtStart",revision);diagnostics.put("revisionAtEnd",endRevision);
        // Enforce an exact serialized context cap as well as incremental admission estimates.
        // Envelope/plan/diagnostics are not LLM evidence context. Never return dangling facts after budget rejection.
        int serializedContext=GraphPolicy.encode(Map.of("nodes",nodeOutput,"relationships",edgeOutput,"paths",paths,"evidence",sourceOutput,"chunks",new ArrayList<>(chunks.values()))).length();
        if(serializedContext>limits.contextChars()){
            nodeOutput=List.of();edgeOutput=List.of();sourceOutput=List.of();paths=List.of();chunks.clear();
            warnings.add("Serialized evidence context exceeded the budget; no partial assertions were emitted. Increase KG_CONTEXT_CHARS.");
            diagnostics.put("warnings",List.copyOf(warnings));diagnostics.put("truncated",true);serializedContext=GraphPolicy.encode(Map.of("nodes",nodeOutput,"relationships",edgeOutput,"paths",paths,"evidence",sourceOutput,"chunks",List.of())).length();
        }
        diagnostics.put("contextCharactersUsed",serializedContext);
        Map<String,Object> graph=new LinkedHashMap<>();graph.put("plan",Map.of("entities",plan.entities(),"aliases",plan.aliases(),"semanticText",plan.semanticText(),"entityTypes",plan.entityTypes(),"relationshipTypes",plan.relationshipTypes(),"direction",plan.direction(),"hops",plan.hops()));graph.put("nodes",nodeOutput);graph.put("relationships",edgeOutput);graph.put("paths",paths);graph.put("evidence",sourceOutput);graph.put("strategy","LEXICAL_EXACT_RRF_BFS");graph.put("ranking",Map.of("fusion","reciprocal-rank fusion","k",limits.fusionK(),"pathScore","seedRrf / (1 + hopCount)"));graph.put("diagnostics",diagnostics);
        return new RetrievalResult(request.query(),request.includeCitations(),new ArrayList<>(chunks.values()),graph);
    }
    private boolean addNode(Node node,double score,Map<String,Node> nodes,Map<String,Double> scores,Map<String,Support> evidence,Map<String,RetrievedChunk> chunks,int[] budget,boolean[] truncated){
        if(nodes.containsKey(node.key())){scores.merge(node.key(),score,Math::max);return true;}
        int cost=GraphPolicy.encode(node).length();if(nodes.size()>=limits.nodes()||cost>budget[0]){truncated[0]=true;return false;}
        if(!addSupports(node.key(),score,evidence,chunks,budget,truncated))return false;
        if(cost>budget[0]){truncated[0]=true;return false;}budget[0]-=cost;nodes.put(node.key(),node);scores.put(node.key(),score);return true;
    }
    private boolean addSupports(String key,double score,Map<String,Support> all,Map<String,RetrievedChunk> chunks,int[] budget,boolean[] truncated){
        List<Support> supports=reader.evidence(key,Math.min(limits.chunks(),8));boolean found=false;
        for(Support e:supports){
            if(all.containsKey(e.id())){found=true;continue;}
            // Defensive check also protects adapters: evidence must match its returned source exactly.
            if(e.start()<0||e.end()>e.text().length()||e.end()<=e.start()||!e.text().substring(e.start(),e.end()).equals(e.quote()))throw new IllegalStateException("Stored graph evidence is inconsistent with its source.");
            int cost=GraphPolicy.encode(e).length();boolean newChunk=!chunks.containsKey(e.chunkId());
            if(newChunk&&chunks.size()>=limits.chunks()||cost>budget[0]){truncated[0]=true;continue;}
            budget[0]-=cost;all.put(e.id(),e);chunks.putIfAbsent(e.chunkId(),new RetrievedChunk(e.chunkId(),e.text(),e.sourceName(),e.page(),score));found=true;
        }return found;
    }
    public static void cancelled(){if(Thread.currentThread().isInterrupted())throw new CancellationException("Graph operation cancelled.");}
}
