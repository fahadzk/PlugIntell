package com.core.docintel.pipeline.web;

import java.net.URI;
import java.util.*;

/** Optional desktop integration without linking AWT into a headless native server. */
final class BrowserLauncher {
    private BrowserLauncher() {}
    static Optional<List<String>> command(String url, String os, Map<String,String> environment) {
        URI uri=URI.create(url);
        if (!"http".equals(uri.getScheme()) || !Set.of("127.0.0.1", "localhost", "[::1]").contains(uri.getHost()))
            throw new IllegalArgumentException("Browser URL must be local HTTP.");
        if (environment.containsKey("CI")) return Optional.empty();
        os=os.toLowerCase(Locale.ROOT);
        if (os.contains("win")) return Optional.of(List.of("rundll32.exe", "url.dll,FileProtocolHandler", url));
        if (os.contains("mac")) return Optional.of(List.of("open", url));
        if (environment.getOrDefault("DISPLAY", "").isBlank() && environment.getOrDefault("WAYLAND_DISPLAY", "").isBlank()) return Optional.empty();
        return Optional.of(List.of("xdg-open", url));
    }
    static void open(String url) {
        try {
            var command=command(url,System.getProperty("os.name"),System.getenv());
            if(command.isPresent())new ProcessBuilder(command.get()).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        } catch (Exception ignored) { /* The host always prints the URL as fallback. */ }
    }
}
