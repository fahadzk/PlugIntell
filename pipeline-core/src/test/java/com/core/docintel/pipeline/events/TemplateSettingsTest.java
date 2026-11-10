package com.core.docintel.pipeline.events;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TemplateSettingsTest {
    private final Map<String,Object> schema=Map.of("type","object","additionalProperties",false,"properties",Map.of(
        "accent",Map.of("type","string","format","color","default","#123456"),
        "count",Map.of("type","integer","minimum",5,"maximum",100),
        "visible",Map.of("type","boolean"),
        "layout",Map.of("type","string","enum",List.of("cards","list"))));
    @Test void validatesTypedSettingsWithoutCoercingBooleansOrResolvingReferences(){
        assertDoesNotThrow(()->TemplateSettings.validate(Map.of("accent","#abcdef","count",30,"visible",false,"layout","cards"),schema));
        for(var invalid:List.of(Map.<String,Object>of("accent","url(https://example.org)"),Map.<String,Object>of("count",101),Map.<String,Object>of("visible","false"),Map.<String,Object>of("layout","unknown"),Map.<String,Object>of("unknown",true)))assertThrows(IllegalArgumentException.class,()->TemplateSettings.validate(invalid,schema));
        assertDoesNotThrow(()->TemplateSettings.validate(Map.of("label","secret://env/NOT_RESOLVED"),null));
    }
    @Test void rejectsUnboundedNestedAndNonFiniteSettings(){
        for(var invalid:List.of(Map.<String,Object>of("nested",Map.of()),Map.<String,Object>of("text","x".repeat(2049)),Map.<String,Object>of("count",Double.NaN),Map.<String,Object>of("count",Double.POSITIVE_INFINITY)))assertThrows(IllegalArgumentException.class,()->TemplateSettings.validate(invalid,null));
    }
    @Test void validatesSchemaBeforeAcceptingDefaultsAndRejectsUnsupportedKeywords(){
        for(var invalid:List.of(Map.of("type","array"),Map.of("type","object","properties",Map.of("title",Map.of("type","string","pattern",".*"))),Map.of("type","object","properties",Map.of("title",Map.of("type","string","default",false))),Map.of("type","object","properties",Map.of("title",Map.of())),Map.of("type","object","required",List.of("missing"))))assertThrows(IllegalArgumentException.class,()->TemplateSettings.validateSchema(invalid));
        Map<Object,Object> malformed=new HashMap<>();malformed.put("type","object");malformed.put(null,true);assertThrows(IllegalArgumentException.class,()->TemplateSettings.validateSchema(malformed));
    }
    @Test void legacySchemasRemainOptionalAndRequiredSettingsAreEnforced(){
        assertDoesNotThrow(()->TemplateSettings.validate(Map.of("legacyColor","blue","customCount",4),null));
        assertThrows(IllegalArgumentException.class,()->TemplateSettings.validate(Map.of(),Map.of("type","object","properties",Map.of("name",Map.of("type","string")),"required",List.of("name"))));
    }
}
