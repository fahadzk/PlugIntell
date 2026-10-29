package com.core.docintel.ollama;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Locale;

/** Small JDK HTTP preflight for the Ollama /api/tags endpoint. */
public final class OllamaModelAvailability {
    private static final ObjectMapper JSON = new ObjectMapper();
    private OllamaModelAvailability() {}

    public static void requireAvailable(OllamaConfig config, String role) {
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(config.timeout()).build()) {
            HttpRequest request = HttpRequest.newBuilder(URI.create(config.baseUrl() + "/api/tags"))
                    .timeout(config.timeout()).GET().build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("Ollama " + role + " preflight failed: " + config.baseUrl()
                        + "/api/tags returned HTTP " + response.statusCode() + ".");
            }
            JsonNode models = JSON.readTree(response.body()).path("models");
            for (JsonNode model : models) {
                String installed = model.path("name").asText("");
                if (matches(config.modelName(), installed)) return;
            }
            throw new IllegalStateException("Ollama is reachable at " + config.baseUrl()
                    + " but " + role + " model '" + config.modelName() + "' is not installed."
                    + " Install it on the Ollama host and retry.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while checking Ollama " + role + " model '"
                    + config.modelName() + "' at " + config.baseUrl() + ".", e);
        } catch (IOException | IllegalArgumentException e) {
            throw new IllegalStateException("Cannot connect to Ollama " + role + " endpoint at "
                    + config.baseUrl() + ": " + e.getMessage(), e);
        }
    }

    private static boolean matches(String requested, String installed) {
        String expected = normalize(requested);
        String actual = normalize(installed);
        return expected.equals(actual);
    }

    private static String normalize(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(":latest") ? lower.substring(0, lower.length() - ":latest".length()) : lower;
    }
}
