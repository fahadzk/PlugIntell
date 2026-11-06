package com.core.docintel.pipeline.builder;

import java.util.*;

/** Resolves secret references recursively without changing the persisted configuration. */
final class SecretReferences {
    private SecretReferences() { }

    static Map<String, Object> resolve(Map<String, Object> config, SecretRedactor redactor) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(redactor, "redactor");
        Map<String, Object> resolved = new LinkedHashMap<>();
        config.forEach((key, value) -> resolved.put(key, resolveValue(value, redactor)));
        return Collections.unmodifiableMap(resolved);
    }

    private static Object resolveValue(Object value, SecretRedactor redactor) {
        if (value instanceof String reference && reference.startsWith("secret://")) {
            String prefix = "secret://env/";
            if (!reference.startsWith(prefix)) {
                throw new IllegalArgumentException("Unsupported secret reference provider. Use secret://env/VARIABLE_NAME.");
            }
            String variable = reference.substring(prefix.length());
            if (!variable.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                throw new IllegalArgumentException("Secret environment reference has an invalid variable name.");
            }
            String secret = System.getenv(variable);
            if (secret == null || secret.isEmpty()) {
                throw new IllegalArgumentException("Secret environment variable '" + variable + "' is not set.");
            }
            redactor.register(secret);
            return secret;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> nested = new LinkedHashMap<>();
            map.forEach((key, item) -> {
                if (!(key instanceof String text)) throw new IllegalArgumentException("Plugin configuration object keys must be strings.");
                nested.put(text, resolveValue(item, redactor));
            });
            return Collections.unmodifiableMap(nested);
        }
        if (value instanceof List<?> list) {
            List<Object> nested = new ArrayList<>(list.size());
            list.forEach(item -> nested.add(resolveValue(item, redactor)));
            return Collections.unmodifiableList(nested);
        }
        return value;
    }
}
