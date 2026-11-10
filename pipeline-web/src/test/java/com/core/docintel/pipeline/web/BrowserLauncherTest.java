package com.core.docintel.pipeline.web;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class BrowserLauncherTest {
    @Test void configuredBrowserCommandsKeepUrlAsOneArgumentAndHeadlessHostsHaveFallback() {
        String url="http://127.0.0.1:8080/ui/monitor.html";
        assertEquals(List.of("rundll32.exe","url.dll,FileProtocolHandler",url),BrowserLauncher.command(url,"Windows 11",Map.of()).orElseThrow());
        assertEquals(List.of("open",url),BrowserLauncher.command(url,"Mac OS X",Map.of()).orElseThrow());
        assertEquals(List.of("xdg-open",url),BrowserLauncher.command(url,"Linux",Map.of("WAYLAND_DISPLAY","wayland-0")).orElseThrow());
        assertTrue(BrowserLauncher.command(url,"Linux",Map.of()).isEmpty());
        assertTrue(BrowserLauncher.command(url,"Windows",Map.of("CI","true")).isEmpty());
        assertThrows(IllegalArgumentException.class,()->BrowserLauncher.command("https://example.org","Windows",Map.of()));
    }
}
