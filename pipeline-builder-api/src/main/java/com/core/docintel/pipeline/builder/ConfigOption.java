package com.core.docintel.pipeline.builder;

import io.micronaut.serde.annotation.Serdeable;

@Serdeable
public record ConfigOption(String name, String type, String defaultValue, boolean required,
                           boolean sensitive, String description, Integer minimum, Integer maximum) {
    public ConfigOption(String name,String defaultValue,boolean required,boolean sensitive,String description){this(name,"string",defaultValue,required,sensitive,description,null,null);}
    public ConfigOption(String name,String type,String defaultValue,boolean required,boolean sensitive,String description){this(name,type,defaultValue,required,sensitive,description,null,null);}
}
