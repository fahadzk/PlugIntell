package com.core.docintel.pipeline.kafka;
import java.util.*;
/** Only telemetry connection settings; arbitrary Kafka class names are not accepted. */
public record KafkaConfig(String bootstrapServers,String topic,String clientId,String securityProtocol,String saslMechanism,String username,String password,String truststoreLocation,String truststorePassword) {
 public KafkaConfig{
  if(bootstrapServers==null||bootstrapServers.isBlank()||bootstrapServers.length()>2000)throw new IllegalArgumentException("bootstrapServers is required.");
  if(topic==null||!topic.matches("[A-Za-z0-9_.-]{1,249}"))throw new IllegalArgumentException("Invalid Kafka topic.");
  if(!Set.of("PLAINTEXT","SSL","SASL_SSL","SASL_PLAINTEXT").contains(securityProtocol))throw new IllegalArgumentException("Unsupported securityProtocol.");
  if(!Set.of("PLAIN","SCRAM-SHA-256","SCRAM-SHA-512").contains(saslMechanism))throw new IllegalArgumentException("Unsupported saslMechanism.");
 }
 public static KafkaConfig from(Map<String,Object> c){return new KafkaConfig(value(c,"bootstrapServers",""),value(c,"topic",""),value(c,"clientId","plugintell-events"),value(c,"securityProtocol","PLAINTEXT"),value(c,"saslMechanism","PLAIN"),value(c,"username",""),value(c,"password",""),value(c,"truststoreLocation",""),value(c,"truststorePassword",""));}
 private static String value(Map<String,Object> c,String key,String fallback){return Objects.toString(c.get(key),fallback);}
 private static String secret(String value){if(value.isEmpty())return "";if(!value.matches("secret://env/[A-Za-z_][A-Za-z0-9_]*"))throw new IllegalArgumentException("Kafka credentials must use secret://env/NAME references.");String name=value.substring("secret://env/".length()),resolved=System.getenv(name);if(resolved==null||resolved.isEmpty())throw new IllegalArgumentException("Required secret environment variable '"+name+"' is not set.");return resolved;}
 private static String quote(String value){return value.replace("\\","\\\\").replace("\"","\\\"");}
 public Properties properties(){
  Properties p=new Properties();p.put("bootstrap.servers",bootstrapServers);p.put("client.id",clientId);p.put("security.protocol",securityProtocol);p.put("acks","all");p.put("enable.idempotence","true");p.put("retries","2");p.put("max.in.flight.requests.per.connection","1");p.put("request.timeout.ms","3000");p.put("delivery.timeout.ms","5000");p.put("max.block.ms","1000");p.put("buffer.memory","1048576");
  if(securityProtocol.startsWith("SASL")){p.put("sasl.mechanism",saslMechanism);String module=saslMechanism.equals("PLAIN")?"org.apache.kafka.common.security.plain.PlainLoginModule":"org.apache.kafka.common.security.scram.ScramLoginModule";p.put("sasl.jaas.config",module+" required username=\""+quote(secret(username))+"\" password=\""+quote(secret(password))+"\";");}
  if(!truststoreLocation.isBlank())p.put("ssl.truststore.location",truststoreLocation);if(!truststorePassword.isEmpty())p.put("ssl.truststore.password",secret(truststorePassword));return p;
 }
}

