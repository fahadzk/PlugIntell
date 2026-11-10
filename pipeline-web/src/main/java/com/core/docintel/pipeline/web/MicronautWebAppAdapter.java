package com.core.docintel.pipeline.web;
import com.core.docintel.pipeline.events.ApplicationServices;
import io.micronaut.runtime.Micronaut;
import io.micronaut.runtime.server.EmbeddedServer;
import java.util.*;
/** Optional host; application wiring remains explicit. */
public final class MicronautWebAppAdapter implements AutoCloseable{
 private final WebConfig config;private final ApplicationServices services;private io.micronaut.context.ApplicationContext context;private HostExecutionLogs logs;private final java.util.concurrent.atomic.AtomicBoolean closed=new java.util.concurrent.atomic.AtomicBoolean();
 public MicronautWebAppAdapter(WebConfig config,ApplicationServices services){this.config=config;this.services=services;}
 public void serve() throws InterruptedException{
  logs=new HostExecutionLogs(java.nio.file.Path.of(System.getenv().getOrDefault("PIPELINE_LOG_DIR","logs")));
  services.addEventDestination("_host-console",()->logs.adapter());
  logs.info("Starting pipeline web server on loopback port "+config.port());
  context=Micronaut.build(new String[0]).deduceEnvironment(false).environments("generated-pipeline").singletons(services,config).properties(Map.of("micronaut.server.host","127.0.0.1","micronaut.server.port",config.port(),"micronaut.application.name","generated-pipeline","micronaut.router.static-resources.monitor.mapping","/ui/**","micronaut.router.static-resources.monitor.paths","classpath:public")).start();
  String url=context.getBean(EmbeddedServer.class).getURI()+"/ui/monitor.html";System.out.println("Pipeline web app: "+url);logs.info("Pipeline web app ready: "+url);
  if(config.openBrowser())BrowserLauncher.open(url);
  Runtime.getRuntime().addShutdownHook(new Thread(this::close));new java.util.concurrent.CountDownLatch(1).await();
 }
 @Override public void close(){if(closed.compareAndSet(false,true)){try{services.close();if(context!=null)context.close();}finally{if(logs!=null)logs.close();}}}
}

