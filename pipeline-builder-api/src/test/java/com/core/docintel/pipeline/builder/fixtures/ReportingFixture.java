package com.core.docintel.pipeline.builder.fixtures;
import com.core.docintel.pipeline.api.*;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
public final class ReportingFixture extends AbstractPlugin implements ConfigurablePlugin,ParallelSafePlugin {
 private static final AtomicInteger calls=new AtomicInteger();
 private String name="fixture";private boolean failFirst,ignoreInterrupt;private long delay;
 public ReportingFixture(){}
 public ReportingFixture(PluginMessages messages){super(messages);}
 public PluginManifest manifest(){return new PluginManifest("reporting-fixture","Reporting fixture","1.0.0","1.0",getClass().getName(),"SearchRequest","SearchRequest");}
 public void configure(Map<String,Object> c){name=c.getOrDefault("name","fixture").toString();failFirst=Boolean.TRUE.equals(c.get("failFirst"));ignoreInterrupt=Boolean.TRUE.equals(c.get("ignoreInterrupt"));delay=((Number)c.getOrDefault("delay",0)).longValue();}
 public Object execute(Object input){
  emit("fixture.stage",Map.of("name",name));progress(0,(Long)null,"items");
  if(failFirst&&calls.incrementAndGet()==1){Thread.ofVirtual().start(()->{try{Thread.sleep(50);}catch(InterruptedException e){Thread.currentThread().interrupt();}emit("fixture.obsolete",Map.of("name",name));});throw new IllegalStateException("temporary fixture failure");}
  long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(delay);
  while(System.nanoTime()<deadline)try{Thread.sleep(5);}catch(InterruptedException e){if(!ignoreInterrupt){Thread.currentThread().interrupt();throw new java.util.concurrent.CancellationException("interrupted");}}
  progress(1,1,"items");return input;
 }
}

