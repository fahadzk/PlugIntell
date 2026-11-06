package com.core.docintel.pipeline.builder;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SecretRedactorTest {
    @Test void redactsLongResolvedSecretsBeforeMessagesArePersisted(){
        SecretRedactor redactor=new SecretRedactor();
        redactor.register("super-secret-token");
        redactor.register("super-secret");
        assertEquals("Authentication failed for [REDACTED]",redactor.redact("Authentication failed for super-secret-token"));
        assertEquals("missing credential",redactor.redact("missing credential"));
    }
}
