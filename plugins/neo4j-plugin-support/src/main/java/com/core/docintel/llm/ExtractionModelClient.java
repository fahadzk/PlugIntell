package com.core.docintel.llm;

import com.core.docintel.neo4j.*;
import com.core.docintel.ollama.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;

/** Shared structured-generation provider adapter for graph extraction and Text-to-Cypher. */
public final class ExtractionModelClient implements AutoCloseable {
    private final GraphSettings settings;
    private final String provider,key;
    private final OllamaStructuredClient ollama;
    private final HttpClient http;
    private final URI modelUrl,generateUrl;
    private volatile boolean verified;
    public static String provider(Map<String,Object> config){return GraphPolicy.choice(config,"LLM_PROVIDER","OLLAMA","OLLAMA","OPENAI","GEMINI");}
    public static GraphSettings settings(Map<String,Object> config){return switch(provider(config)){
        case "OPENAI"->new GraphSettings(config,"OPENAI_BASE_URL","https://api.openai.com","OPENAI_MODEL","OPENAI_TIMEOUT_SECONDS");
        case "GEMINI"->new GraphSettings(config,"GEMINI_BASE_URL","https://generativelanguage.googleapis.com","GEMINI_MODEL","GEMINI_TIMEOUT_SECONDS");
        default->new GraphSettings(config);
    };}
    public ExtractionModelClient(Map<String,Object> config,GraphSettings settings){
        this.settings=settings;provider=provider(config);
        if(provider.equals("OLLAMA")){ollama=new OllamaStructuredClient(new OllamaConfig(settings.baseUrl,settings.model,settings.timeout));key="";http=null;modelUrl=null;generateUrl=null;return;}
        ollama=null;String keyName=provider+"_API_KEY";key=GraphPolicy.text(config,keyName,System.getenv().getOrDefault(keyName,""));
        if(key.isBlank()||key.length()>4096||key.startsWith("secret://")||key.chars().anyMatch(c->c<33||c>126))throw new IllegalArgumentException(keyName+" is required; use a secret://env/"+keyName+" reference resolved by the host.");
        String model=settings.model.startsWith("models/")&&provider.equals("GEMINI")?settings.model.substring(7):settings.model;
        if(!model.matches(provider.equals("OPENAI")?"[A-Za-z0-9][A-Za-z0-9._:/-]{0,199}":"[A-Za-z0-9][A-Za-z0-9._:-]{0,199}")||Arrays.asList(model.split("/",-1)).stream().anyMatch(s->s.isEmpty()||s.equals(".")||s.equals("..")))throw new IllegalArgumentException(provider+"_MODEL must be a valid model ID. Model names are configurable; no model allowlist is imposed.");
        URI base;try{base=URI.create(settings.baseUrl);}catch(IllegalArgumentException e){throw new IllegalArgumentException("Invalid "+provider+"_BASE_URL.");}
        boolean local=Set.of("localhost","127.0.0.1","::1","[::1]").contains(Objects.toString(base.getHost(),""));
        boolean origin=base.getPath().isEmpty()||base.getPath().equals("/");
        if(base.getHost()==null||base.getUserInfo()!=null||base.getQuery()!=null||base.getFragment()!=null||provider.equals("GEMINI")&&!origin
            ||!("https".equals(base.getScheme())||"http".equals(base.getScheme())&&local))throw new IllegalArgumentException(provider+"_BASE_URL must be an HTTPS "+(provider.equals("OPENAI")?"API base URL":"origin")+" without credentials, query parameters or fragments. HTTP is allowed only for local test servers.");
        // Resolve relative endpoints so compatible API prefixes survive and /v1 is added only for origins.
        URI apiBase=provider.equals("GEMINI")?base.resolve("/v1beta/"):origin?base.resolve("/v1/")
            :URI.create(base.toString()+(base.getRawPath().endsWith("/")?"":"/"));
        modelUrl=apiBase.resolve("models/"+java.net.URLEncoder.encode(model,java.nio.charset.StandardCharsets.UTF_8).replace("+","%20"));
        generateUrl=provider.equals("GEMINI")?URI.create(modelUrl+":generateContent"):apiBase.resolve("chat/completions");
        http=HttpClient.newBuilder().connectTimeout(settings.timeout).followRedirects(HttpClient.Redirect.NEVER).build();
    }
    public synchronized void verifyContextCapacity(int context){
        if(ollama!=null){ollama.verifyContextCapacity(context);return;}if(verified)return;
        JsonNode model=send(HttpRequest.newBuilder(modelUrl).GET(),262144);
        if(provider.equals("GEMINI")){
            boolean generate=false;for(JsonNode method:model.path("supportedGenerationMethods"))if(method.asText().equals("generateContent"))generate=true;
            if(!generate||model.path("inputTokenLimit").asLong()<context||model.path("outputTokenLimit").asLong()<settings.outputTokens)throw new IllegalStateException("Gemini model metadata does not confirm generateContent and the configured input/output limits. Choose a compatible model or reduce KG_CONTEXT_TOKENS/KG_OUTPUT_TOKENS.");
        }else if(!model.path("id").isTextual()||model.path("id").asText().isBlank())throw new IllegalStateException("OpenAI did not confirm access to OPENAI_MODEL.");
        // OpenAI model metadata does not expose context capacity; local conservative budgets still apply.
        verified=true;
    }
    public JsonNode request(String system,String data,Map<String,Object> schema,double temperature,int context,int output,int maxBytes){
        if(ollama!=null)return ollama.request(system,data,schema,temperature,context,output,maxBytes);
        verifyContextCapacity(context);settings.budget(system,data);
        Object payload;
        if(provider.equals("GEMINI"))payload=Map.of("systemInstruction",Map.of("parts",List.of(Map.of("text",system))),"contents",List.of(Map.of("role","user","parts",List.of(Map.of("text",data)))),
            "generationConfig",Map.of("temperature",temperature,"maxOutputTokens",output,"candidateCount",1,"responseMimeType","application/json","responseJsonSchema",schema));
        else payload=Map.of("model",settings.model,"messages",List.of(Map.of("role","system","content",system),Map.of("role","user","content",data)),"max_completion_tokens",output,"store",false,
            "response_format",Map.of("type","json_schema","json_schema",Map.of("name","knowledge_graph_extraction","strict",true,"schema",strictSchema(GraphPolicy.JSON.valueToTree(schema)))));
        byte[] body;try{body=GraphPolicy.JSON.writeValueAsBytes(payload);}catch(java.io.IOException e){throw new IllegalArgumentException("Could not encode extraction request.");}
        if((long)body.length+output+512>context)throw new IllegalArgumentException("Cloud extraction request and schema exceed KG_CONTEXT_TOKENS. Use smaller chunks or a model-compatible larger context budget; no source text was truncated.");
        JsonNode envelope=send(HttpRequest.newBuilder(generateUrl).POST(HttpRequest.BodyPublishers.ofByteArray(body)),maxBytes);
        String content;
        if(provider.equals("GEMINI")){
            if(envelope.path("promptFeedback").has("blockReason"))throw new IllegalStateException("Gemini blocked the extraction request. No output was accepted.");
            JsonNode candidates=envelope.path("candidates");if(!candidates.isArray()||candidates.size()!=1||!candidates.get(0).path("finishReason").asText().equals("STOP"))throw new IllegalStateException("Gemini extraction was incomplete or blocked. Check output budget and model access.");
            StringBuilder text=new StringBuilder();for(JsonNode part:candidates.get(0).path("content").path("parts"))if(!part.path("thought").asBoolean()&&part.path("text").isTextual())text.append(part.path("text").asText());content=text.toString();
        }else{
            JsonNode choices=envelope.path("choices");if(!choices.isArray()||choices.size()!=1||!choices.get(0).path("finish_reason").asText().equals("stop"))throw new IllegalStateException("OpenAI extraction was incomplete. Check output budget or model support.");
            JsonNode message=choices.get(0).path("message");if(!message.path("refusal").isMissingNode()&&!message.path("refusal").isNull())throw new IllegalStateException("OpenAI refused the extraction request. No output was accepted.");
            if(!message.path("content").isTextual())throw new IllegalArgumentException("OpenAI returned no structured content.");content=message.path("content").asText();
        }
        JsonNode result;try{result=GraphPolicy.JSON.readTree(content);if(result==null)throw new IllegalArgumentException("Empty extraction JSON.");}catch(java.io.IOException e){throw new IllegalArgumentException(provider+" returned malformed extraction JSON.");}
        if(provider.equals("OPENAI"))removeAbsentProperties(result,schema);
        return result;
    }
    /** OpenAI requires all object fields; optional scalar properties become nullable on the wire only. */
    private static JsonNode strictSchema(JsonNode schema){
        ObjectNode result=((ObjectNode)schema).deepCopy();result.remove("maxProperties");
        if(result.path("properties").isObject()){
            Set<String> required=new HashSet<>();for(JsonNode name:result.path("required"))required.add(name.asText());
            ObjectNode properties=(ObjectNode)result.get("properties");List<String> names=new ArrayList<>();properties.fieldNames().forEachRemaining(names::add);
            for(String name:names){JsonNode child=strictSchema(properties.get(name));if(!required.contains(name))child=GraphPolicy.JSON.valueToTree(Map.of("anyOf",List.of(child,Map.of("type","null"))));properties.set(name,child);}
            result.set("required",GraphPolicy.JSON.valueToTree(names));
        }
        if(result.path("items").isObject())result.set("items",strictSchema(result.get("items")));
        return result;
    }
    private static void removeAbsentProperties(JsonNode result,Map<String,Object> schema){
        JsonNode root=GraphPolicy.JSON.valueToTree(schema);
        for(String lane:List.of("entities","relationships")){
            JsonNode propertySchema=root.path("properties").path(lane).path("items").path("properties").path("properties");
            Set<String> required=new HashSet<>();for(JsonNode name:propertySchema.path("required"))required.add(name.asText());
            if(result.path(lane).isArray())for(JsonNode record:result.path(lane))if(record.path("properties").isObject()){
                ObjectNode properties=(ObjectNode)record.get("properties");List<String> absent=new ArrayList<>();properties.fields().forEachRemaining(e->{if(e.getValue().isNull()&&propertySchema.path("properties").has(e.getKey())&&!required.contains(e.getKey()))absent.add(e.getKey());});absent.forEach(properties::remove);
            }
        }
    }
    private JsonNode send(HttpRequest.Builder builder,int limit){
        if(Thread.currentThread().isInterrupted())throw new CancellationException("Extraction request cancelled.");CompletableFuture<HttpResponse<byte[]>> pending=null;
        try{
            builder.timeout(settings.timeout).header("Content-Type","application/json");if(provider.equals("GEMINI"))builder.header("x-goog-api-key",key);else builder.header("Authorization","Bearer "+key);
            HttpRequest request=builder.build();pending=http.sendAsync(request,info->new LimitedBody(limit));var response=pending.get(settings.timeout.toMillis(),TimeUnit.MILLISECONDS);int code=response.statusCode();
            if(code/100!=2){
                boolean generation=request.method().equals("POST");
                String operation=generation?(provider.equals("GEMINI")?"generateContent":"chat completions"):"model metadata lookup";
                String status=safeErrorStatus(response.body());
                String model=settings.model.replace(key,"<redacted>");
                throw new IllegalStateException(provider+" HTTP "+code+(status.isEmpty()?"":" ("+status+")")+" during "+operation+" for "+provider+"_MODEL="+model+". "+switch(code){
                    case 400->"Check structured-output support, model and generation settings.";
                    case 401,403->"Check the selected provider API key and model/project access.";
                    case 404->provider.equals("GEMINI")?(generation&&verified?"Model metadata lookup succeeded, but generation is unavailable for this model/API key or endpoint. ":"The configured model/API endpoint was not found. ")
                        +"Run tools/test-gemini.ps1 -Model <configured-model> -TestGeneration in the same shell as the application; use -ListModels to inspect model IDs. Listing or reading model metadata does not guarantee generation access. Confirm GEMINI_BASE_URL is the Gemini Developer API origin and the model is enabled for this key/project. No model was substituted."
                        :"Check the selected model ID and its availability for this API key.";
                    case 429->"Quota or rate limit reached; retry later or reduce KG_CONCURRENCY.";
                    default->"Provider request failed; check connectivity or retry later.";
                });
            }
            JsonNode json=GraphPolicy.JSON.readTree(response.body());if(json==null)throw new IllegalArgumentException("Empty provider JSON.");return json;
        }catch(InterruptedException e){if(pending!=null)pending.cancel(true);Thread.currentThread().interrupt();throw new CancellationException("Extraction request interrupted.");}
        catch(TimeoutException e){if(pending!=null)pending.cancel(true);throw new IllegalStateException(provider+" request timed out. Increase the provider timeout or retry later.");}
        catch(ExecutionException e){throw new IllegalStateException(provider+" transport failed or exceeded KG_RESPONSE_BYTES.");}
        catch(java.io.IOException e){throw new IllegalArgumentException(provider+" returned malformed JSON.");}
    }
    /** Retain only a known machine status, never provider messages, request text, URLs or details. */
    private static String safeErrorStatus(byte[] body){
        try{String status=GraphPolicy.JSON.readTree(body).path("error").path("status").asText("");
            return Set.of("NOT_FOUND","INVALID_ARGUMENT","UNAUTHENTICATED","PERMISSION_DENIED","RESOURCE_EXHAUSTED","FAILED_PRECONDITION","UNAVAILABLE","INTERNAL","DEADLINE_EXCEEDED").contains(status)?status:"";
        }catch(Exception ignored){return "";}
    }
    public void close(){if(ollama!=null)ollama.close();if(http!=null)http.shutdownNow();}
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final HttpResponse.BodySubscriber<byte[]> delegate=HttpResponse.BodySubscribers.ofByteArray();private final int limit;private long count;private Flow.Subscription subscription;private boolean failed;
        LimitedBody(int limit){this.limit=limit;}
        public CompletionStage<byte[]> getBody(){return delegate.getBody();}public void onSubscribe(Flow.Subscription s){subscription=s;delegate.onSubscribe(s);}
        public void onNext(List<ByteBuffer> buffers){if(failed)return;for(var b:buffers)count+=b.remaining();if(count>limit){failed=true;subscription.cancel();delegate.onError(new IllegalStateException("Response byte limit exceeded."));}else delegate.onNext(buffers);}
        public void onError(Throwable t){if(!failed)delegate.onError(t);}public void onComplete(){if(!failed)delegate.onComplete();}
    }
}
