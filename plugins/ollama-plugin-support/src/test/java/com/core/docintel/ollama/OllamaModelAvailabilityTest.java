package com.core.docintel.ollama;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class OllamaModelAvailabilityTest {
    @Test void acceptsInstalledLatestTag() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/tags", exchange -> {
            byte[] body = "{\"models\":[{\"name\":\"nomic-embed-text:latest\"}]}".getBytes();
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) { out.write(body); }
        });
        server.start();
        try {
            var config = new OllamaConfig("http://127.0.0.1:" + server.getAddress().getPort(), "nomic-embed-text", Duration.ofSeconds(2));
            assertDoesNotThrow(() -> OllamaModelAvailability.requireAvailable(config, "embedding"));
        } finally {
            server.stop(0);
        }
    }

    @Test void reportsMissingModel() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/tags", exchange -> {
            byte[] body = "{\"models\":[]}".getBytes();
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) { out.write(body); }
        });
        server.start();
        try {
            var config = new OllamaConfig("http://127.0.0.1:" + server.getAddress().getPort(), "missing", Duration.ofSeconds(2));
            var error = assertThrows(IllegalStateException.class, () -> OllamaModelAvailability.requireAvailable(config, "chat"));
            assertTrue(error.getMessage().contains("missing"));
        } finally {
            server.stop(0);
        }
    }

    @Test void reportsUnreachableOllamaEndpoint() {
        var config = new OllamaConfig("http://127.0.0.1:1", "model", Duration.ofMillis(300));
        var error = assertThrows(IllegalStateException.class,
                () -> OllamaModelAvailability.requireAvailable(config, "embedding"));
        assertTrue(error.getMessage().contains("Cannot connect to Ollama embedding endpoint"));
    }
}
