package com.core.docintel.pipeline.events;

import java.util.*;

/** Small, declarative settings-schema subset shared by template import and publication. */
public final class TemplateSettings {
    private TemplateSettings() {}
    private static final Set<String> TYPES=Set.of("string","boolean","integer","number");

    public static void validateSchema(Object value) {
        if(value==null)return;
        if(!(value instanceof Map<?,?> schema)||!"object".equals(schema.get("type")))throw invalid("settingsSchema must describe an object");
        if(schema.keySet().stream().anyMatch(key->!(key instanceof String))||!Set.of("type","properties","additionalProperties","required").containsAll(schema.keySet()))throw invalid("Unsupported settingsSchema keyword");
        if(schema.containsKey("additionalProperties")&&!(schema.get("additionalProperties") instanceof Boolean))throw invalid("additionalProperties must be boolean");
        Map<?,?> properties=properties(schema);
        if(properties.size()>32)throw invalid("At most 32 settings are supported");
        for(var entry:properties.entrySet()) {
            if(!(entry.getKey() instanceof String name)||!name.matches("[A-Za-z][A-Za-z0-9_-]{0,47}")||!(entry.getValue() instanceof Map<?,?> rules))throw invalid("Invalid setting definition");
            if(rules.keySet().stream().anyMatch(key->!(key instanceof String))||!Set.of("type","title","description","default","enum","maxLength","minimum","maximum","format").containsAll(rules.keySet())||!TYPES.contains(Objects.toString(rules.get("type"),"")))throw invalid("Unsupported setting type or keyword: "+name);
            for(String label:List.of("title","description"))if(rules.containsKey(label)&&(!(rules.get(label) instanceof String text)||text.length()>500))throw invalid("Invalid setting label: "+name);
            if(rules.containsKey("format")&&(!"string".equals(rules.get("type"))||!"color".equals(rules.get("format"))))throw invalid("Only string/color format is supported");
            if(rules.containsKey("maxLength")&&(!(rules.get("maxLength") instanceof Number n)||n.doubleValue()<0||n.doubleValue()>2048||n.doubleValue()!=Math.rint(n.doubleValue())))throw invalid("Invalid maxLength");
            for(String bound:List.of("minimum","maximum"))if(rules.containsKey(bound)&&(!(rules.get(bound) instanceof Number n)||!Double.isFinite(n.doubleValue())))throw invalid("Invalid numeric bound");
            if(rules.get("enum")!=null){if(!(rules.get("enum") instanceof List<?> choices)||choices.isEmpty()||choices.size()>32)throw invalid("Invalid setting enum");for(Object choice:choices)check(name,choice,rules,false);}
            if(rules.containsKey("default"))check(name,rules.get("default"),rules,true);
        }
        if(schema.containsKey("required")){if(!(schema.get("required") instanceof List<?> required)||required.size()>32||required.stream().anyMatch(key->!properties.containsKey(key)))throw invalid("Invalid required settings");}
    }

    public static void validate(Map<String,Object> settings,Object schemaValue) {
        if(settings.size()>32)throw invalid("At most 32 settings are supported");
        for(var entry:settings.entrySet())if(!(entry.getValue() instanceof String text&&text.length()<=2048)&&!(entry.getValue() instanceof Boolean)&&!(entry.getValue() instanceof Number number&&Double.isFinite(number.doubleValue())))throw invalid("Settings must be bounded strings, booleans or finite numbers");
        validateSchema(schemaValue);
        if(!(schemaValue instanceof Map<?,?> schema))return;
        Map<?,?> properties=properties(schema);
        for(var entry:settings.entrySet()) {
            if(properties.get(entry.getKey()) instanceof Map<?,?> rules)check(entry.getKey(),entry.getValue(),rules,true);
            else if(Boolean.FALSE.equals(schema.get("additionalProperties")))throw invalid("Unknown setting: "+entry.getKey());
        }
        if(schema.get("required") instanceof List<?> required)for(Object name:required)if(!settings.containsKey(name))throw invalid("Missing required setting: "+name);
    }

    private static Map<?,?> properties(Map<?,?> schema){Object value=schema.get("properties");if(value==null)return Map.of();if(value instanceof Map<?,?> map)return map;throw invalid("properties must be an object");}
    private static void check(String name,Object value,Map<?,?> rules,boolean enumeration) {
        String type=Objects.toString(rules.get("type"));
        boolean valid=switch(type){case "string"->value instanceof String;case "boolean"->value instanceof Boolean;case "integer"->value instanceof Number n&&Double.isFinite(n.doubleValue())&&n.doubleValue()==Math.rint(n.doubleValue());case "number"->value instanceof Number n&&Double.isFinite(n.doubleValue());default->false;};
        if(!valid)throw invalid("Invalid value for "+name+" (expected "+type+")");
        if(value instanceof String text){int max=rules.get("maxLength") instanceof Number n?n.intValue():2048;if(text.length()>max)throw invalid("Setting is too long: "+name);if("color".equals(rules.get("format"))&&!text.matches("#[0-9A-Fa-f]{6}"))throw invalid("Use a six-digit hex color for "+name);}
        if(value instanceof Number number){if(rules.get("minimum") instanceof Number min&&number.doubleValue()<min.doubleValue()||rules.get("maximum") instanceof Number max&&number.doubleValue()>max.doubleValue())throw invalid("Setting outside allowed range: "+name);}
        if(enumeration&&rules.get("enum") instanceof List<?> choices&&choices.stream().noneMatch(choice->Objects.equals(value,choice)||value instanceof Number n&&choice instanceof Number c&&Double.compare(n.doubleValue(),c.doubleValue())==0))throw invalid("Setting outside allowed choices: "+name);
    }
    private static IllegalArgumentException invalid(String message){return new IllegalArgumentException("Template settings: "+message+".");}
}
