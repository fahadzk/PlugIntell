package com.core.docintel.pipeline.builder;

import io.micronaut.serde.annotation.Serdeable;

@Serdeable
public record ConfigOption(String name, String type, String defaultValue, boolean required,
                           boolean sensitive, String description, Integer minimum, Integer maximum, java.util.List<String> allowedValues, String format) {
    public ConfigOption { allowedValues=allowedValues==null?java.util.List.of():java.util.List.copyOf(allowedValues);format=java.util.Objects.toString(format,""); }
    public ConfigOption(String name,String type,String defaultValue,boolean required,boolean sensitive,String description,Integer minimum,Integer maximum,java.util.List<String> allowedValues){this(name,type,defaultValue,required,sensitive,description,minimum,maximum,allowedValues,"");}
    public ConfigOption(String name,String type,String defaultValue,boolean required,boolean sensitive,String description,Integer minimum,Integer maximum){this(name,type,defaultValue,required,sensitive,description,minimum,maximum,java.util.List.of());}
    public ConfigOption(String name,String defaultValue,boolean required,boolean sensitive,String description){this(name,"string",defaultValue,required,sensitive,description,null,null);}
    public ConfigOption(String name,String type,String defaultValue,boolean required,boolean sensitive,String description){this(name,type,defaultValue,required,sensitive,description,null,null);}
}
