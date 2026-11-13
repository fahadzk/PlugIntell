package com.core.docintel.response;
import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import java.util.*;
/** Internal, predictable JSON; no default typing, polymorphic input or bean discovery. */
final class ResponseJson {
    static final ObjectMapper MAPPER=new ObjectMapper(JsonFactory.builder().streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(40).maxStringLength(8388608).build()).build());
    static Object parse(String text){try{return MAPPER.readValue(text,Object.class);}catch(Exception e){throw new IllegalArgumentException("Invalid or excessively nested JSON.");}}
    static byte[] bytes(Object value){try{return MAPPER.writeValueAsBytes(value);}catch(Exception e){throw new IllegalArgumentException("Response is not JSON serializable.");}}
    static String pretty(Object value){try{return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(value);}catch(Exception e){return "Unsupported value";}}
    static Map<String,Object> object(Object raw){if(!(raw instanceof Map<?,?> map))throw new IllegalArgumentException("Expected object.");Map<String,Object> out=new LinkedHashMap<>();map.forEach((k,v)->out.put(k.toString(),v));return out;}
    static String text(Object value){return Objects.toString(value,"");}
}
