package com.core.docintel.pipeline.builder.helpers;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.regex.Pattern;

/** Pure catalog snapshot consumer; never reads plugin metadata from disk. */
public final class LayaPluginSelector {
    public record Candidate(String id, String name, String version, String description,
                            List<String> capabilities, List<String> workflows, String input, String output) {}
    private record Step(String key, String label, String lane, String input, String output) {}
    private static final List<Step> STEPS = List.of(
        new Step("extraction","Text extraction","ingestion","File","TextDocument"),
        new Step("chunking","Chunking","ingestion","TextDocument","ChunkCollection"),
        new Step("embedding","Embedding","ingestion","ChunkCollection","EmbeddingCollection"),
        new Step("writer","Vector write","ingestion","EmbeddingCollection","StoredResult"),
        new Step("retrieval","Retrieval","query","SearchRequest","RetrievalResult"),
        new Step("answer","Answer generation","query","RetrievalResult","AnswerResult"));
    private final URI endpoint;
    private final Duration timeout;
    private final String credential;
    private final HttpClient client;
    private final ObjectMapper json = new ObjectMapper();
    public LayaPluginSelector() { this("http://localhost:11434", 60, ""); }
    public LayaPluginSelector(String baseUrl, int timeoutSeconds, String credential) {
        String normalized=baseUrl==null?"":baseUrl.strip();
        if(normalized.length()>=2 && ((normalized.startsWith("\"")&&normalized.endsWith("\""))
            ||(normalized.startsWith("'")&&normalized.endsWith("'"))))
            normalized=normalized.substring(1,normalized.length()-1).strip();
        URI base;
        try {
            base=URI.create(normalized);
            if (!Set.of("http","https").contains(Objects.toString(base.getScheme(),"")) || base.getHost()==null
                || base.getUserInfo()!=null || base.getQuery()!=null || base.getFragment()!=null)
                throw new IllegalArgumentException();
        } catch(IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid LAYA_BASE_URL. Use an HTTP(S) server URL such as http://localhost:11434 without credentials, query parameters or fragments.");
        }
        if(timeoutSeconds<1 || timeoutSeconds>600) throw new IllegalArgumentException("Laya timeout must be 1-600 seconds.");
        endpoint=URI.create(normalized.replaceAll("/+$", "")+"/v1/systemone");
        timeout=Duration.ofSeconds(timeoutSeconds); this.credential=credential;
        client=HttpClient.newBuilder().connectTimeout(timeout).build();
    }
    public Map<String,Object> recommend(String prompt,List<Candidate> snapshot) {
        if(prompt==null || prompt.isBlank() || prompt.length()>12000) throw new IllegalArgumentException("Describe your RAG workflow in 1-12000 characters.");
        List<Candidate> catalog=List.copyOf(snapshot);
        Map<String,Set<String>> requested=new HashMap<>();
        for(Step step:STEPS) {
            Set<String> ids=new LinkedHashSet<>();
            catalog.stream().filter(c->supportsRole(c,step)).filter(c->explicitlyNamed(prompt,c)).forEach(c->ids.add(c.id()));
            if(ids.size()>1)throw new IllegalArgumentException("Multiple plugins explicitly requested for "+step.label()+": "+String.join(", ",ids)+". Request one plugin for this step.");
            requested.put(step.key(),ids);
        }
        // Provider names are explicit constraints, never silently substituted. Extend this vocabulary as providers are added.
        String storage=provider(prompt,List.of("neo4j","chromadb","chroma","pinecone","qdrant","weaviate","milvus","faiss","pgvector","elasticsearch"));
        if("chroma".equals(storage)) storage="chromadb";
        String model=provider(prompt,List.of("ollama","openai","anthropic","gemini","cohere","bedrock","azure"));
        Map<String,List<Map<String,Object>>> lanes=new LinkedHashMap<>();
        lanes.put("ingestion",new ArrayList<>());lanes.put("query",new ArrayList<>());
        Candidate previous=null,writer=null;
        for(Step step:STEPS) {
            if(step.key().equals("retrieval")) previous=null;
            String required=Set.of("writer","retrieval").contains(step.key())?storage:Set.of("embedding","answer").contains(step.key())?model:null;
            final Candidate prev=previous, selectedWriter=writer;
            final String constraint=required;
            Set<String> named=requested.get(step.key());
            List<Candidate> candidates=catalog.stream().filter(c->c.workflows().contains(step.lane().equals("ingestion")?"DOCUMENT_INGESTION":"QUERY_PROCESSING"))
                .filter(c->supportsRole(c,step))
                .filter(c->named.isEmpty()||named.contains(c.id()))
                .filter(c->prev==null||prev.output().equals(c.input()))
                .filter(c->constraint==null||mentions(c,constraint))
                .filter(c->!step.key().equals("writer")||catalog.stream().anyMatch(r->r.input().equals("SearchRequest")&&r.output().equals("RetrievalResult")&&r.workflows().contains("QUERY_PROCESSING")&&sameStore(c,r)))
                .filter(c->!step.key().equals("retrieval")||selectedWriter==null||sameStore(selectedWriter,c)).toList();
            Map<String,Object> item=new LinkedHashMap<>();item.put("step",step.label());
            if(candidates.isEmpty()) {
                item.put("missing","No compatible installed plugin for "+step.label()+(required==null?"":" (provider: "+required+")")+(named.isEmpty()?"":" (requested: "+String.join(", ",named)+")")+". Install a compatible plugin or revise the prompt.");
                previous=null;
            } else {
                Candidate chosen=choose(prompt,step,candidates);

                item.put("id",chosen.id());item.put("name",chosen.name());item.put("version",chosen.version());
                previous=chosen;if(step.key().equals("writer"))writer=chosen;
            }
            lanes.get(step.lane()).add(item);
        }
        Map<String,Object> result=new LinkedHashMap<>();
        lanes.forEach((lane,items)->result.put(lane,Map.of("complete",items.stream().noneMatch(i->i.containsKey("missing")),"steps",items)));
        return result;
    }
    private Candidate choose(String prompt,Step step,List<Candidate> candidates) {
        // The documented choice API requires 2-26 options. A sole eligible plugin is deterministic.
        if(candidates.size()==1)return candidates.getFirst();
        if(candidates.size()>26)throw new IllegalStateException("Too many candidates for "+step.label()+". Narrow the installed catalog and try again.");
        Map<String,String> criteria=new LinkedHashMap<>();
        candidates.forEach(c->criteria.put(c.id(),c.name()+" v"+c.version()+": "+c.description()+"; ports: "+c.input()+" -> "+c.output()+"; capabilities: "+String.join(", ",c.capabilities())));
        try {
            var request=HttpRequest.newBuilder(endpoint).timeout(timeout).header("Content-Type","application/json");
            if(credential!=null&&!credential.isBlank())request.header("Authorization","Bearer "+credential);
            var response=client.send(request.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("model","laya","state",prompt,"questions",Map.of(step.key(),Map.of("type","choice","instructions","Choose the best installed plugin for "+step.label()+" in this RAG workflow.","criteria",criteria)))))).build(),HttpResponse.BodyHandlers.ofString());
            if(response.statusCode()/100!=2)throw new IllegalStateException("Laya returned HTTP "+response.statusCode()+". Check the backend Laya URL, model and credentials, then try again.");
            String id=json.readTree(response.body()).path("answers").path(step.key()).path("choice").asText();
            return candidates.stream().filter(c->c.id().equals(id)).findFirst().orElseThrow(()->new IllegalStateException("Laya returned an unknown or incompatible plugin for "+step.label()+". Try again."));
        } catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException("Laya request interrupted. Try again.");}
        catch(java.io.IOException e){throw new IllegalStateException("Laya is unreachable or returned invalid JSON. Check LAYA_BASE_URL, network access and LAYA_TIMEOUT_SECONDS on the backend, then try again.");}
    }
    private static boolean supportsRole(Candidate c,Step step) {
        return c.input().equals(step.input())&&c.output().equals(step.output());
    }
    private static boolean explicitlyNamed(String prompt,Candidate c) {
        List<String> aliases=new ArrayList<>(List.of(c.id(),c.name()));
        aliases.addAll(Arrays.asList(c.name().split("(?i)\\s+and\\s+")));
        for(String technology:List.of("Tika","LangChain4j"))
            if(mentions(c,technology.toLowerCase(Locale.ROOT)))aliases.add(technology);
        String normalized=normalize(prompt);
        return aliases.stream().map(LayaPluginSelector::normalize).filter(a->!a.isBlank())
            .anyMatch(a->(" "+normalized+" ").contains(" "+a+" "));
    }
    private static String normalize(String text) {
        return text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+"," ").strip();
    }
    private static String provider(String prompt,List<String> names) {
        return names.stream().filter(n->Pattern.compile("(?i)(?<![a-z0-9])"+Pattern.quote(n)+"(?![a-z0-9])").matcher(prompt).find()).findFirst().orElse(null);
    }
    private static boolean mentions(Candidate c,String provider) {
        String text=(c.id()+" "+c.name()+" "+c.description()+" "+String.join(" ",c.capabilities())).toLowerCase(Locale.ROOT);
        return text.contains(provider)||provider.equals("chromadb")&&text.contains("chroma");
    }
    private static boolean sameStore(Candidate a,Candidate b) {
        // Sharing a server name does not make externally managed graph data compatible with embedding ingestion.
        if(a.input().equals("EmbeddingCollection")&&b.capabilities().contains("knowledge-graph-retrieval"))return false;
        // Storage identity uses the catalog ID naming convention, not the model's assertion.
        String prefix=a.id().replaceFirst("-(writer|write|storage)$", "");
        return !prefix.equals(a.id())&&b.id().startsWith(prefix+"-");
    }
}
