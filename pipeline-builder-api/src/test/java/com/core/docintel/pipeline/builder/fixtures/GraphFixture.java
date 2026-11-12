package com.core.docintel.pipeline.builder.fixtures;
import com.core.docintel.pipeline.api.*;
import java.util.*;
public final class GraphFixture extends AbstractPlugin {
    public PluginManifest manifest(){return new PluginManifest("fixture-graph","Fixture graph","1.0.0","1.0",getClass().getName(),"File","ExtractedGraph");}
    public Object execute(Object value){return new ExtractedGraph("d",Map.of("domain","Test","source","provided"),List.of(),List.of(),List.of(new TextChunk("c","d","source.pdf",1,0,"PRIVATE_DOCUMENT_BODY")),Map.of("complete",false,"chunks_failed",1));}
}
