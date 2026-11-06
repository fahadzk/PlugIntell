package com.core.docintel.pipeline.builder;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class SecretReferencesTest {
    @Test void recursivelyResolvesReferencesInsideObjectsAndArrays() {
        var redactor = new SecretRedactor();
        var config = Map.<String, Object>of("nested", Map.of("items", List.of("secret://vault/token")));

        var failure = assertThrows(IllegalArgumentException.class, () -> SecretReferences.resolve(config, redactor));

        assertTrue(failure.getMessage().contains("Unsupported secret reference provider"));
    }

    @Test void preservesPlainNestedConfigurationAndReturnsImmutableContainers() {
        var nested = Map.<String, Object>of("enabled", true, "labels", List.of("a", "b"));
        var resolved = SecretReferences.resolve(Map.of("settings", nested), new SecretRedactor());

        assertEquals(nested, resolved.get("settings"));
        assertThrows(UnsupportedOperationException.class, () -> resolved.put("new", "value"));
        assertThrows(UnsupportedOperationException.class, () -> ((Map<String, Object>) resolved.get("settings")).put("new", "value"));
    }
}
