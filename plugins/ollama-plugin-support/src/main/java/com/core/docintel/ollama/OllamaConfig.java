package com.core.docintel.ollama;

import java.time.Duration;

public record OllamaConfig(String baseUrl, String modelName, Duration timeout) {
    public OllamaConfig {
        if (baseUrl == null || baseUrl.isBlank()) throw new IllegalArgumentException("Ollama base URL is required.");
        if (modelName == null || modelName.isBlank()) throw new IllegalArgumentException("Ollama model name is required.");
        if (timeout == null || timeout.isZero() || timeout.isNegative()) throw new IllegalArgumentException("Ollama timeout must be positive.");
        baseUrl = baseUrl.replaceAll("/+$", "");
    }

    public static OllamaConfig fromEnvironment(String modelVariable, String role) {
        String baseUrl = System.getenv().getOrDefault("OLLAMA_BASE_URL", "http://localhost:11434");
        String model = System.getenv().getOrDefault(modelVariable, "").trim();
        if (model.isEmpty()) throw new IllegalStateException("Set " + modelVariable + " to the installed Ollama " + role + " model name.");
        long seconds = Long.parseLong(System.getenv().getOrDefault("OLLAMA_TIMEOUT_SECONDS", "60"));
        return new OllamaConfig(baseUrl, model, Duration.ofSeconds(seconds));
    }
}
