package com.core.docintel.pipeline.builder.fixtures;

import com.core.docintel.pipeline.api.*;
import java.util.Map;

public final class QueryFixture extends AbstractPlugin implements ConfigurablePlugin {
    public QueryFixture(){}
    public QueryFixture(PluginMessages messages){super(messages);}
    private String prefix="unset";
    public PluginManifest manifest(){return new PluginManifest("fixture-query","Fixture query","1.0.0","1.0",getClass().getName(),"SearchRequest","String");}
    public void configure(Map<String,Object> config){prefix=String.valueOf(config.get("prefix"));if(config.containsKey("count"))prefix+=":"+((Integer)config.get("count"));if(config.containsKey("OLLAMA_TIMEOUT_SECONDS"))prefix+=":"+config.get("OLLAMA_TIMEOUT_SECONDS");if(Boolean.TRUE.equals(config.get("failWithSecret")))throw new IllegalArgumentException("Rejected fixture secret "+config.get("secretValue"));}
    public Object execute(Object input){emit("fixture.query",Map.of("count",1));return prefix+":"+((SearchRequest)input).query();}
}
