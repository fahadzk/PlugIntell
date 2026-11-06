package com.core.docintel.pipeline.builder.fixtures;

import com.core.docintel.pipeline.api.*;
import java.util.Map;

public final class IngestFixture implements ConfigurablePlugin {
    private String prefix="unset";
    public PluginManifest manifest(){return new PluginManifest("fixture-ingest","Fixture ingestion","1.0.0","1.0",getClass().getName(),"File","String");}
    public void configure(Map<String,Object> config){prefix=String.valueOf(config.get("prefix"));}
    public Object execute(Object input){return prefix+":"+((java.io.File)input).getName();}
}
