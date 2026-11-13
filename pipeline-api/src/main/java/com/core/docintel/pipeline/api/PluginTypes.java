package com.core.docintel.pipeline.api;
import java.util.*;
/** Explicit port unions; output alternatives must all be accepted by the input port. */
public final class PluginTypes {
    private PluginTypes(){}
    public static boolean accepts(String input,String output){
        if(input==null||output==null)return false;
        Set<String> accepted=new HashSet<>(Arrays.asList(input.split("\\|",-1)));
        return Arrays.stream(output.split("\\|",-1)).allMatch(type->!type.isBlank()&&accepted.contains(type));
    }
}
