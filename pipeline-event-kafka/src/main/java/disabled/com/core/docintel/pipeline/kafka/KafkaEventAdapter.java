package com.core.docintel.pipeline.kafka;
import com.core.docintel.pipeline.events.*;
/** Build-time disabled variant. No Kafka dependency is included without -Pkafka. */
public final class KafkaEventAdapter implements EventAdapter{
 public static boolean available(){return false;}
 public KafkaEventAdapter(KafkaConfig config){throw new IllegalStateException("Kafka support is not packaged. Build PlugIntell with Maven -Pkafka.");}
 public void accept(ExecutionEvent event){throw new IllegalStateException("Kafka support is not packaged.");}
 public void testConnection(){throw new IllegalStateException("Kafka support is not packaged.");}
}

