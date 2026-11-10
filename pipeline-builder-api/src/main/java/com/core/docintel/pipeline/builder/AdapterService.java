package com.core.docintel.pipeline.builder;
import com.core.docintel.pipeline.events.*;
import com.core.docintel.pipeline.kafka.*;
import jakarta.inject.Singleton;
import java.util.*;
@Singleton
public final class AdapterService{
 public List<Map<String,Object>> catalog(){return List.of(item("ui","Local UI","Update a configurable Local UI view with built-in or external templates",Map.of(),true),item("kafka","Kafka / Confluent","Publish telemetry with run-based partition keys",Map.of("bootstrapServers",property("string","localhost:9092"),"topic",property("string","plugintell.events"),"clientId",property("string","plugintell-events"),"securityProtocol",Map.of("type","string","enum",List.of("PLAINTEXT","SSL","SASL_SSL","SASL_PLAINTEXT"),"default","PLAINTEXT"),"saslMechanism",Map.of("type","string","enum",List.of("PLAIN","SCRAM-SHA-256","SCRAM-SHA-512"),"default","PLAIN"),"username",secret(),"password",secret(),"truststoreLocation",property("string",""),"truststorePassword",secret()),KafkaEventAdapter.available()),item("noop","No-op","Discard selected events",Map.of(),true));}
 private static Map<String,Object> property(String type,String value){return Map.of("type",type,"default",value);}
 private static Map<String,Object> secret(){return Map.of("type","string","format","secret-reference","sensitive",true,"description","secret://env/NAME");}
 private static Map<String,Object> item(String type,String name,String description,Map<String,Object> properties,boolean available){return Map.of("type",type,"name",name,"version","1","description",description,"available",available,"configurationSchema",Map.of("type","object","properties",properties,"additionalProperties",false));}
 public Map<String,Object> test(String type,Map<String,Object> config){try{switch(type){case "ui","noop"->{if(!config.isEmpty())throw new IllegalArgumentException("No configuration expected.");}case "kafka"->{CapabilitySettings.validateKafka(config);try(KafkaEventAdapter adapter=new KafkaEventAdapter(KafkaConfig.from(config))){adapter.testConnection();}}default->throw new IllegalArgumentException("Unknown adapter type.");}return Map.of("success",true,"message","Connection check succeeded.");}catch(Exception e){return Map.of("success",false,"message","Connection check failed: "+e.getClass().getSimpleName());}}
 public void wire(Map<String,Object> capabilities,PipelineType workflow,EventDispatcher dispatcher,PipelineDescriptor descriptor,WorkflowStateStore state){Map<String,Object> c=capabilities==null?Map.of():capabilities;
  for(Map<String,Object> definition:CapabilitySettings.objects(c.get("adapters"))){String id=CapabilitySettings.text(definition,"id",""),type=CapabilitySettings.text(definition,"type","");List<EventFilter> filters=CapabilitySettings.filters(c,id,workflow);if(filters.isEmpty())continue;
   dispatcher.attachLazy(id,()->switch(type){case "ui"->new UIMessageAdapter(descriptor,state);case "kafka"->new KafkaEventAdapter(KafkaConfig.from(CapabilitySettings.map(definition.get("config"))));case "noop"->new NoopEventAdapter();default->throw new IllegalArgumentException("Unsupported adapter.");},filters);
  }
 }
}

