package com.core.docintel.ollama;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;

/** Shared bounded, non-streaming structured-output transport. No source/response bodies in errors. */
public final class OllamaStructuredClient implements AutoCloseable {
    private final OllamaConfig config;
    private final HttpClient http;
    private volatile int verifiedContext;
    private final ObjectMapper json=new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    public OllamaStructuredClient(OllamaConfig config) {
        URI base;try{base=URI.create(config.baseUrl());}catch(IllegalArgumentException e){throw new IllegalArgumentException("OLLAMA_BASE_URL is not a valid server URL.");}
        if(!Set.of("http","https").contains(Objects.toString(base.getScheme(),"")) || base.getHost()==null
                || base.getUserInfo()!=null || base.getQuery()!=null || base.getFragment()!=null)
            throw new IllegalArgumentException("OLLAMA_BASE_URL must be an HTTP(S) server URL without credentials or query parameters.");
        this.config=config;http=HttpClient.newBuilder().connectTimeout(config.timeout()).build();
    }
    public JsonNode request(String system,String data,Map<String,Object> schema,double temperature,int context,int output,int maxBytes) {
        JsonNode envelope=post("/api/chat",Map.of("model",config.modelName(),"stream",false,"format",schema,
                "messages",List.of(Map.of("role","system","content",system),Map.of("role","user","content",data)),
                "options",Map.of("temperature",temperature,"num_ctx",context,"num_predict",output)),maxBytes);
        if("length".equals(envelope.path("done_reason").asText()))throw new IllegalArgumentException("Structured output exceeded its output budget.");
        JsonNode content=envelope.path("message").path("content");
        if(!content.isTextual()||content.asText().isBlank())throw new IllegalArgumentException("Ollama returned no structured content.");
        try{return json.readTree(content.asText());}catch(java.io.IOException e){throw new IllegalArgumentException("Ollama returned malformed structured JSON.");}
    }
    public synchronized void verifyContextCapacity(int requested){
        if(verifiedContext>=requested)return;
        JsonNode metadata=post("/api/show",Map.of("model",config.modelName()),262144);
        boolean completion=false;for(JsonNode capability:metadata.path("capabilities"))if(capability.asText().equals("completion"))completion=true;
        long capacity=0;var fields=metadata.path("model_info").fields();while(fields.hasNext()){var field=fields.next();if(field.getKey().endsWith(".context_length")&&field.getValue().isIntegralNumber())capacity=Math.max(capacity,field.getValue().asLong());}
        if(!completion||capacity<requested)throw new IllegalStateException("Ollama model metadata does not confirm completion support and the configured context capacity. Choose a compatible model/context; input was not truncated.");
        verifiedContext=requested;
    }
    private JsonNode post(String path,Map<String,Object> payload,int maxBytes){
        if(Thread.currentThread().isInterrupted())throw new CancellationException("Structured request cancelled.");
        CompletableFuture<HttpResponse<byte[]>> pending=null;
        try {
            byte[] body=json.writeValueAsBytes(payload);
            var request=HttpRequest.newBuilder(URI.create(config.baseUrl()+path)).timeout(config.timeout())
                    .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
            pending=http.sendAsync(request,info->new LimitedBody(maxBytes));
            var response=pending.get(config.timeout().toMillis(),TimeUnit.MILLISECONDS);
            if(response.statusCode()/100!=2)throw new IllegalStateException("Ollama structured request failed with HTTP "+response.statusCode()+".");
            return json.readTree(response.body());
        } catch(InterruptedException e){if(pending!=null)pending.cancel(true);Thread.currentThread().interrupt();throw new CancellationException("Structured request interrupted.");}
        catch(TimeoutException e){if(pending!=null)pending.cancel(true);throw new IllegalStateException("Ollama structured request timed out.");}
        catch(ExecutionException e){throw new IllegalStateException("Ollama structured transport failed or response exceeded its byte budget.");}
        catch(java.io.IOException e){throw new IllegalArgumentException("Ollama returned malformed structured JSON.");}
    }
    @Override public void close(){http.shutdownNow();}
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final HttpResponse.BodySubscriber<byte[]> delegate=HttpResponse.BodySubscribers.ofByteArray();
        private final long limit;private long count;private Flow.Subscription subscription;private boolean failed;
        LimitedBody(long limit){if(limit<1)throw new IllegalArgumentException("Response byte budget must be positive.");this.limit=limit;}
        public CompletionStage<byte[]> getBody(){return delegate.getBody();}
        public void onSubscribe(Flow.Subscription s){subscription=s;delegate.onSubscribe(s);}
        public void onNext(List<ByteBuffer> buffers){if(failed)return;for(var b:buffers)count+=b.remaining();if(count>limit){failed=true;subscription.cancel();delegate.onError(new IllegalStateException("Response byte budget exceeded."));}else delegate.onNext(buffers);}
        public void onError(Throwable t){if(!failed)delegate.onError(t);}
        public void onComplete(){if(!failed)delegate.onComplete();}
    }
}
