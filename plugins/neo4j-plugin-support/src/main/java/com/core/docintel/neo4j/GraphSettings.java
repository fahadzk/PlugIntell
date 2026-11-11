package com.core.docintel.neo4j;
import java.time.Duration;
import java.util.Map;

/** Shared graph plugin settings; credentials are deliberately absent from toString and result provenance. */
public final class GraphSettings {
    public final Neo4jConfig neo4j;public final String baseUrl,model,index,domain,prompt;
    public final int contextTokens,outputTokens,retries,responseBytes,inputChars,batchSize,concurrency;
    public final Duration timeout,queryTimeout;public final double temperature;
    public final boolean continueErrors,provision;
    public GraphSettings(Map<String,Object> c){
        this(c,"OLLAMA_BASE_URL","http://localhost:11434","OLLAMA_CHAT_MODEL","OLLAMA_TIMEOUT_SECONDS");
    }
    public GraphSettings(Map<String,Object> c,String baseKey,String defaultBase,String modelKey,String timeoutKey){
        baseUrl=value(c,baseKey,defaultBase);model=value(c,modelKey,"");
        if(model.isBlank()||model.length()>200)throw new IllegalArgumentException(modelKey+" is required and must be at most 200 characters.");
        timeout=Duration.ofSeconds(GraphPolicy.number(c,timeoutKey,60,1,600));queryTimeout=Duration.ofSeconds(GraphPolicy.number(c,"KG_QUERY_TIMEOUT_SECONDS",20,1,120));
        neo4j=new Neo4jConfig(value(c,"NEO4J_URI","bolt://localhost:7687"),value(c,"NEO4J_USERNAME","neo4j"),value(c,"NEO4J_PASSWORD",""),value(c,"NEO4J_DATABASE","neo4j"),"document_embedding_idx",768);
        try{var uri=java.net.URI.create(neo4j.uri());if(uri.getHost()==null||uri.getUserInfo()!=null||uri.getFragment()!=null)throw new IllegalArgumentException();}catch(IllegalArgumentException e){throw new IllegalArgumentException("NEO4J_URI must be a valid server URI without embedded credentials.");}
        index=KnowledgeGraphStore.approvedIndex(GraphPolicy.text(c,"KG_FULLTEXT_INDEX",System.getenv().getOrDefault("PLUGINTELL_KG_FULLTEXT_INDEX","plugintell_kg_entities")));
        contextTokens=GraphPolicy.number(c,"KG_CONTEXT_TOKENS",16384,2048,131072);outputTokens=GraphPolicy.number(c,"KG_OUTPUT_TOKENS",2048,128,16384);
        if(outputTokens>=contextTokens)throw new IllegalArgumentException("KG_OUTPUT_TOKENS must be smaller than KG_CONTEXT_TOKENS.");
        retries=GraphPolicy.number(c,"KG_REPAIR_RETRIES",1,0,3);responseBytes=GraphPolicy.number(c,"KG_RESPONSE_BYTES",262144,1024,2097152);
        inputChars=GraphPolicy.number(c,"KG_MAX_INPUT_CHARS",6000,1,32000);batchSize=GraphPolicy.number(c,"KG_MAX_BATCH_CHUNKS",256,1,1024);concurrency=GraphPolicy.number(c,"KG_CONCURRENCY",1,1,8);
        continueErrors=GraphPolicy.choice(c,"KG_FAILURE_POLICY","FAIL","FAIL","CONTINUE").equals("CONTINUE");
        provision=GraphPolicy.choice(c,"KG_PROVISION_SCHEMA","false","false","true").equals("true");
        temperature=Double.parseDouble(GraphPolicy.text(c,"KG_TEMPERATURE","0"));if(!Double.isFinite(temperature)||temperature<0||temperature>1)throw new IllegalArgumentException("KG_TEMPERATURE must be 0-1.");
        domain=GraphPolicy.text(c,"KG_DOMAIN_INSTRUCTIONS","");prompt=GraphPolicy.text(c,"KG_PROMPT_TEMPLATE","");
        if(domain.length()>8000||prompt.length()>8000)throw new IllegalArgumentException("Graph prompt instructions exceed 8000 characters.");
    }
    public void budget(String system,String data){
        if((long)system.getBytes(java.nio.charset.StandardCharsets.UTF_8).length+data.getBytes(java.nio.charset.StandardCharsets.UTF_8).length+outputTokens+512>contextTokens)
            throw new IllegalArgumentException("Input and schema exceed the conservative UTF-8 context budget; use smaller upstream chunks or a supported larger context. No text was truncated.");
    }
    private static String value(Map<String,Object> c,String key,String fallback){return GraphPolicy.text(c,key,System.getenv().getOrDefault(key,fallback));}
}
