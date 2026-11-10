package com.core.docintel.pipeline.kafka;
import com.core.docintel.pipeline.events.*;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class KafkaEventAdapterTest{
 @Test void serializesVersionedEventsWithRunKeysAndObservesAsyncCallbackFailures(){
  KafkaConfig config=KafkaConfig.from(Map.of("bootstrapServers","localhost:9092","topic","events"));
  var producer=new MockProducer<String,String>(false,new StringSerializer(),new StringSerializer());List<ExecutionEvent> events=new ArrayList<>();new RunEvents("run-key","pipeline",3,null,events::add).attempt("node","plugin",1);
  try(var adapter=new KafkaEventAdapter(config,producer)){adapter.accept(events.getFirst());assertEquals("run-key",producer.history().getFirst().key());assertTrue(producer.history().getFirst().value().contains("\"schemaVersion\":1"));producer.errorNext(new IllegalStateException("offline"));assertEquals(1L,adapter.health().get("failed"));adapter.accept(events.getFirst());producer.completeNext();assertEquals(1L,adapter.health().get("delivered"));}
 }
 @Test void producerHasFiniteBuffersRetriesAndDeliveryDeadlines(){var properties=KafkaConfig.from(Map.of("bootstrapServers","localhost:9092","topic","events")).properties();assertEquals("1000",properties.get("max.block.ms"));assertEquals("5000",properties.get("delivery.timeout.ms"));assertEquals("2",properties.get("retries"));assertEquals("1048576",properties.get("buffer.memory"));assertTrue(KafkaEventAdapter.available());}
}

