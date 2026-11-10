package com.core.docintel.pipeline.kafka;
import com.core.docintel.pipeline.events.*;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
@EnabledIfEnvironmentVariable(named="PLUGINTELL_KAFKA_TEST_BOOTSTRAP",matches=".+")
class KafkaBrokerIntegrationTest{
 @Test void publishesTelemetryToConfiguredBrokerAndConsumesSameRunKey()throws Exception{
  String protocol=System.getenv().getOrDefault("PLUGINTELL_KAFKA_TEST_SECURITY_PROTOCOL","PLAINTEXT"),topic=System.getenv().getOrDefault("PLUGINTELL_KAFKA_TEST_TOPIC","plugintell-event-smoke");
  Map<String,Object> settings=new HashMap<>(Map.of("bootstrapServers",System.getenv("PLUGINTELL_KAFKA_TEST_BOOTSTRAP"),"topic",topic,"securityProtocol",protocol));
  if(protocol.startsWith("SASL")){settings.put("username","secret://env/PLUGINTELL_KAFKA_TEST_USERNAME");settings.put("password","secret://env/PLUGINTELL_KAFKA_TEST_PASSWORD");}
  KafkaConfig config=KafkaConfig.from(settings);Properties consumerConfig=new Properties();Properties source=config.properties();for(String key:List.of("bootstrap.servers","security.protocol","sasl.mechanism","sasl.jaas.config","ssl.truststore.location","ssl.truststore.password"))if(source.containsKey(key))consumerConfig.put(key,source.get(key));consumerConfig.put("group.id","plugintell-smoke-"+UUID.randomUUID());consumerConfig.put("auto.offset.reset","earliest");consumerConfig.put("enable.auto.commit","false");
  String runId=UUID.randomUUID().toString();List<ExecutionEvent> events=new ArrayList<>();new RunEvents(runId,"smoke",1,null,events::add).attempt("node","fixture",1);
  try(var consumer=new KafkaConsumer<String,String>(consumerConfig,new StringDeserializer(),new StringDeserializer());var adapter=new KafkaEventAdapter(config)){consumer.subscribe(List.of(topic));adapter.accept(events.getFirst());long deadline=System.nanoTime()+Duration.ofSeconds(15).toNanos();boolean found=false;while(System.nanoTime()<deadline&&!found)for(var record:consumer.poll(Duration.ofMillis(500)))if(runId.equals(record.key())&&record.value().contains(events.getFirst().eventId()))found=true;assertTrue(found,"Expected telemetry event was not consumed.");assertEquals(0L,adapter.health().get("failed"));}
 }
}

