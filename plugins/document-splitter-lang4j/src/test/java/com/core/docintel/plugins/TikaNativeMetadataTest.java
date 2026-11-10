package com.core.docintel.plugins;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import static org.junit.jupiter.api.Assertions.*;

class TikaNativeMetadataTest {
    @Test void nativeActivationFeatureCanInspectBundledMailcapHandlers() throws Exception {
        var loader=getClass().getClassLoader();
        var resources=loader.getResources("META-INF/mailcap");
        var handler=Pattern.compile("x-java-content-handler=([^;\\s]+)");
        int inspected=0;
        while(resources.hasMoreElements()) {
            String text;
            try(var input=resources.nextElement().openStream()) { text=new String(input.readAllBytes(),StandardCharsets.UTF_8); }
            var match=handler.matcher(text);
            while(match.find()) {
                // Angus performs this inspection during native analysis. Loading
                // constructors/method signatures must not fail on missing Mail types.
                var type=Class.forName(match.group(1),false,loader);
                assertNotNull(type.getDeclaredConstructors());
                assertNotNull(type.getDeclaredMethods());
                inspected++;
            }
        }
        assertTrue(inspected>0,"Tika's mailcap handlers must be covered");
    }
}
