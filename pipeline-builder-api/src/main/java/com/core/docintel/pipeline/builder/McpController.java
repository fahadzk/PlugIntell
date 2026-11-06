package com.core.docintel.pipeline.builder;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micronaut.context.annotation.Value;
import io.micronaut.http.*;
import io.micronaut.http.annotation.*;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Bearer-authenticated MCP Streamable HTTP adapter with stateless 2026 and legacy 2025 support. */
@Controller("/mcp")
public final class McpController {
    public static final String PROTOCOL = "2026-07-28";
    public static final String LEGACY_PROTOCOL = "2025-11-25";
    private static final String SERVER_INFO = "io.modelcontextprotocol/serverInfo";
    private static final String REQUEST_VERSION = "io.modelcontextprotocol/protocolVersion";
    private static final String CLIENT_INFO = "io.modelcontextprotocol/clientInfo";
    private static final String CLIENT_CAPABILITIES = "io.modelcontextprotocol/clientCapabilities";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> ALLOWED_ORIGINS = Set.of("http://localhost:8080", "http://127.0.0.1:8080", "http://[::1]:8080");
    private static final Set<String> LOCAL_HOSTS = Set.of("localhost:8080", "127.0.0.1:8080", "[::1]:8080");

    private final McpApplicationService service;
    private final ControlPlaneAuthService auth;
    private final String token;
    private final Set<String> extraOrigins;
    private final Set<String> allowedHosts;
    private final Map<String, String> sessions = new ConcurrentHashMap<>();

    @Inject
    public McpController(McpApplicationService service, ControlPlaneAuthService auth,
                         @Value("${mcp.bearer-token:}") String token,
                         @Value("${mcp.allowed-origins:}") String origins, @Value("${mcp.allowed-hosts:}") String hosts) {
        this.service = service;
        this.auth = auth;
        this.token = token;
        this.extraOrigins = csv(origins);
        this.allowedHosts = csv(hosts);
    }

    /** Direct constructor for isolated protocol tests and embedded use without RBAC. */
    public McpController(McpApplicationService service, String token, String origins, String hosts) {
        this(service, null, token, origins, hosts);
    }

    /** Convenience entry point for direct controller contract tests. */
    public HttpResponse<?> post(HttpRequest<?> request) {
        Object body = request.getBody().isPresent() ? request.getBody().get() : Map.of();
        return post(request, body);
    }

    @Post(consumes = MediaType.APPLICATION_JSON, produces = MediaType.APPLICATION_JSON)
    public HttpResponse<?> post(HttpRequest<?> request, @Body Object rawBody) {
        if (!originHostAllowed(request)) return HttpResponse.status(HttpStatus.FORBIDDEN);
        if ((auth == null || !auth.enabled()) && (token == null || token.isBlank())) return HttpResponse.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("error", "MCP_BEARER_TOKEN is required to enable the MCP endpoint."));
        McpPrincipal principal = authenticate(request);
        if (principal == null) return HttpResponse.unauthorized();
        String accept = request.getHeaders().get("Accept");
        if (accept == null || !accept.contains(MediaType.APPLICATION_JSON) || !accept.contains("text/event-stream")) return HttpResponse.status(HttpStatus.NOT_ACCEPTABLE);

        Map<String, Object> body = map(rawBody);
        Object id = body.get("id");
        String method = Objects.toString(body.get("method"), "");
        Map<String, Object> params = map(body.get("params"));
        if (!"2.0".equals(body.get("jsonrpc")) || method.isBlank()) return error(id, -32600, "Invalid JSON-RPC request", HttpStatus.BAD_REQUEST);
        if (method.startsWith("notifications/")) return HttpResponse.accepted();
        if (id == null) return error(null, -32600, "JSON-RPC request ID is required", HttpStatus.BAD_REQUEST);
        if ("tools/call".equals(method) && !authorizeToolCall(principal, params, id)) return HttpResponse.status(HttpStatus.FORBIDDEN)
                .contentType(MediaType.APPLICATION_JSON_TYPE).body(Map.of("jsonrpc", "2.0", "id", id,
                        "error", Map.of("code", -32003, "message", "The authenticated role cannot invoke this MCP tool.")));

        Map<String, Object> metadata = map(params.get("_meta"));
        String requestedVersion = Objects.toString(metadata.get(REQUEST_VERSION), "");
        boolean modern = !requestedVersion.isBlank() || PROTOCOL.equals(request.getHeaders().get("MCP-Protocol-Version"));
        if (modern) {
            HttpResponse<?> invalid = validateModernRequest(request, method, params, metadata, requestedVersion, id);
            if (invalid != null) return invalid;
            if (Set.of("initialize", "ping").contains(method)) return error(id, -32601, "Method not found: " + method, HttpStatus.NOT_FOUND);
            try {
                Object result = withModernEnvelope(method, dispatch(method, params));
                return jsonResponse(Map.of("jsonrpc", "2.0", "id", id, "result", result));
            } catch (NoSuchElementException e) {
                return error(id, -32601, e.getMessage(), HttpStatus.NOT_FOUND);
            } catch (IllegalArgumentException e) {
                return error(id, -32602, e.getMessage(), HttpStatus.BAD_REQUEST);
            } catch (Exception e) {
                return error(id, -32603, "MCP operation failed: " + safe(e), HttpStatus.INTERNAL_SERVER_ERROR);
            }
        }

        // Handshake-era compatibility for clients using MCP 2025-11-25.
        if ("initialize".equals(method)) {
            try {
                Object result = dispatch(method, params);
                Map<String, Object> response = Map.of("jsonrpc", "2.0", "id", id, "result", result);
                MutableHttpResponse<Map<String, Object>> http = jsonResponse(response);
                String session = UUID.randomUUID().toString();
                sessions.put(session, LEGACY_PROTOCOL);
                http.header("Mcp-Session-Id", session);
                http.header("MCP-Protocol-Version", LEGACY_PROTOCOL);
                return http;
            } catch (IllegalArgumentException e) {
                return error(id, -32602, e.getMessage(), HttpStatus.BAD_REQUEST);
            }
        }
        String session = request.getHeaders().get("Mcp-Session-Id");
        String headerVersion = request.getHeaders().get("MCP-Protocol-Version");
        if (session == null || !sessions.containsKey(session)) return error(id, -32001, "Unknown or expired MCP session", HttpStatus.NOT_FOUND);
        if (!LEGACY_PROTOCOL.equals(headerVersion)) return error(id, -32600, "MCP-Protocol-Version header is required and must be 2025-11-25", HttpStatus.BAD_REQUEST);
        try {
            Object result = dispatch(method, params);
            MutableHttpResponse<Map<String, Object>> http = jsonResponse(Map.of("jsonrpc", "2.0", "id", id, "result", result));
            http.header("MCP-Protocol-Version", LEGACY_PROTOCOL);
            return http;
        } catch (NoSuchElementException e) {
            return error(id, -32601, e.getMessage(), HttpStatus.NOT_FOUND);
        } catch (IllegalArgumentException e) {
            return error(id, -32602, e.getMessage(), HttpStatus.BAD_REQUEST);
        } catch (Exception e) {
            return error(id, -32603, "MCP operation failed: " + safe(e), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private HttpResponse<?> validateModernRequest(HttpRequest<?> request, String method, Map<String, Object> params,
                                                   Map<String, Object> metadata, String requestedVersion, Object id) {
        String headerVersion = request.getHeaders().get("MCP-Protocol-Version");
        if (headerVersion == null || !headerVersion.equals(requestedVersion)) return error(id, -32020, "MCP-Protocol-Version header does not match request metadata", HttpStatus.BAD_REQUEST);
        String headerMethod = request.getHeaders().get("Mcp-Method");
        if (headerMethod == null || !headerMethod.equals(method)) return error(id, -32020, "Mcp-Method header does not match the JSON-RPC method", HttpStatus.BAD_REQUEST);
        String requestName = switch (method) {
            case "tools/call", "prompts/get" -> Objects.toString(params.get("name"), "");
            case "resources/read" -> Objects.toString(params.get("uri"), "");
            default -> "";
        };
        if (!requestName.isEmpty()) {
            String headerName = request.getHeaders().get("Mcp-Name");
            if (headerName == null || !requestName.equals(decodeHeaderValue(headerName))) return error(id, -32020, "Mcp-Name header does not match the request name or URI", HttpStatus.BAD_REQUEST);
        }
        if (!PROTOCOL.equals(requestedVersion)) {
            return HttpResponse.status(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON_TYPE)
                    .body(Map.of("jsonrpc", "2.0", "id", id, "error", Map.of("code", -32022,
                            "message", "Unsupported protocol version", "data", Map.of("supported", List.of(PROTOCOL, LEGACY_PROTOCOL), "requested", requestedVersion))));
        }
        if (!metadata.containsKey(CLIENT_INFO) || !(metadata.get(CLIENT_INFO) instanceof Map<?, ?>)
                || !metadata.containsKey(CLIENT_CAPABILITIES) || !(metadata.get(CLIENT_CAPABILITIES) instanceof Map<?, ?>)) {
            return error(id, -32600, "Modern MCP requests require clientInfo and clientCapabilities metadata", HttpStatus.BAD_REQUEST);
        }
        return null;
    }

    private Object withModernEnvelope(String method, Object value) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (value instanceof Map<?, ?> map) map.forEach((k, v) -> result.put(String.valueOf(k), v));
        else result.put("value", value);
        result.put("resultType", "complete");
        if (Set.of("server/discover", "tools/list", "resources/list", "prompts/list").contains(method)) {
            result.putIfAbsent("ttlMs", 0);
            result.putIfAbsent("cacheScope", "private");
        }
        if ("server/discover".equals(method)) {
            result.put("supportedVersions", List.of(PROTOCOL, LEGACY_PROTOCOL));
            result.put("capabilities", Map.of("tools", Map.of(), "resources", Map.of(), "prompts", Map.of()));
            result.put("_meta", Map.of(SERVER_INFO, Map.of("name", "PlugIntell RAG Pipeline Studio", "version", "1.0.0")));
            result.put("instructions", "Use read-only tools where possible. Pipeline execution is limited to published workflows and configured input roots.");
        }
        return result;
    }

    private MutableHttpResponse<Map<String, Object>> jsonResponse(Map<String, Object> value) {
        MutableHttpResponse<Map<String, Object>> response = HttpResponse.ok(value);
        response.contentType(MediaType.APPLICATION_JSON_TYPE);
        return response;
    }

    @Get(produces = "text/event-stream")
    public HttpResponse<?> get(HttpRequest<?> request) {
        if (!originHostAllowed(request)) return HttpResponse.status(HttpStatus.FORBIDDEN);
        return HttpResponse.status(HttpStatus.METHOD_NOT_ALLOWED);
    }

    /** Legacy clients may explicitly close their handshake session. Modern clients are stateless. */
    @Delete
    public HttpResponse<?> delete(HttpRequest<?> request) {
        if (!originHostAllowed(request)) return HttpResponse.status(HttpStatus.FORBIDDEN);
        if ((auth == null || !auth.enabled()) && (token == null || token.isBlank())) return HttpResponse.status(HttpStatus.SERVICE_UNAVAILABLE);
        if (authenticate(request) == null) return HttpResponse.unauthorized();
        String id = request.getHeaders().get("Mcp-Session-Id");
        if (id == null) return HttpResponse.status(HttpStatus.METHOD_NOT_ALLOWED);
        if (sessions.remove(id) == null) return HttpResponse.notFound();
        return HttpResponse.noContent();
    }

    private Object dispatch(String method, Map<String, Object> p) { return switch (method) {
        case "server/discover" -> Map.of();
        case "initialize" -> {
            String requested = Objects.toString(p.get("protocolVersion"), "");
            if (!LEGACY_PROTOCOL.equals(requested)) throw new IllegalArgumentException("Unsupported protocol version. Supported legacy version: " + LEGACY_PROTOCOL);
            yield Map.of("protocolVersion", LEGACY_PROTOCOL, "capabilities", Map.of("tools", Map.of(), "resources", Map.of(), "prompts", Map.of()),
                    "serverInfo", Map.of("name", "PlugIntell RAG Pipeline Studio", "version", "1.0.0"),
                    "instructions", "Use read-only tools where possible. Pipeline execution is limited to published workflows and configured input roots.");
        }
        case "ping" -> Map.of();
        case "tools/list" -> Map.of("tools", service.tools());
        case "tools/call" -> callTool(p);
        case "resources/list" -> Map.of("resources", service.resources());
        case "resources/read" -> readResource(Objects.toString(p.get("uri"), ""));
        case "prompts/list" -> Map.of("prompts", service.prompts());
        case "prompts/get" -> service.prompt(Objects.toString(p.get("name"), ""), map(p.get("arguments")));
        default -> throw new NoSuchElementException("Method not found: " + method);
    }; }

    private Object callTool(Map<String, Object> p) {
        String name = Objects.toString(p.get("name"), "");
        try {
            Object result = service.call(name, map(p.get("arguments")));
            return Map.of("content", List.of(Map.of("type", "text", "text", json(result))), "isError", false, "structuredContent", normalize(result));
        } catch (RuntimeException e) {
            return Map.of("content", List.of(Map.of("type", "text", "text", safe(e))), "isError", true);
        }
    }

    private Object readResource(String uri) { Object value = service.readResource(uri); return Map.of("contents", List.of(Map.of("uri", uri, "mimeType", "application/json", "text", json(value)))); }

    private HttpResponse<?> error(Object id, int code, String message, HttpStatus status) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", "2.0");
        if (id != null) response.put("id", id);
        response.put("error", Map.of("code", code, "message", message));
        return HttpResponse.status(status).contentType(MediaType.APPLICATION_JSON_TYPE).body(response);
    }

    private McpPrincipal authenticate(HttpRequest<?> request) {
        String value = request.getHeaders().get("Authorization");
        if (value == null || !value.startsWith("Bearer ")) return null;
        String credential = value.substring(7);
        if (auth != null && auth.enabled()) return auth.authenticate(credential).map(McpPrincipal::new).orElse(null);
        if (token == null || token.isBlank()) return null;
        byte[] supplied = credential.getBytes(StandardCharsets.UTF_8), expected = token.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(supplied, expected) ? new McpPrincipal(null) : null;
    }

    private boolean originHostAllowed(HttpRequest<?> request) {
        String origin = request.getHeaders().get("Origin");
        if (origin != null && !ALLOWED_ORIGINS.contains(origin) && !extraOrigins.contains(origin)) return false;
        String host = request.getHeaders().get("Host");
        return host == null || LOCAL_HOSTS.contains(host) || allowedHosts.contains(host);
    }

    private boolean authorizeToolCall(McpPrincipal principal, Map<String, Object> params, Object requestId) {
        if (principal.user() == null || auth == null || !auth.enabled()) return true;
        String name = Objects.toString(params.get("name"), "");
        boolean operation = Set.of("rag_execute_query", "rag_start_ingestion").contains(name);
        String path = "rag_execute_query".equals(name) ? "/api/v1/query/execute"
                : "rag_start_ingestion".equals(name) ? "/api/v1/ingestion/jobs"
                : "/api/v1/mcp/tools/" + name;
        boolean allowed = auth.allowed(principal.user(), path, operation ? "POST" : "GET");
        if (operation) auth.audit(principal.user(), "POST", "/mcp/tools/" + name, Objects.toString(requestId, ""), allowed ? "AUTHORIZED" : "DENIED_FORBIDDEN");
        return allowed;
    }

    private record McpPrincipal(UserSummary user) { }

    @SuppressWarnings("unchecked") private static Map<String, Object> map(Object value) {
        if (value instanceof Map<?, ?> m) return (Map<String, Object>) m;
        if (value == null) return Map.of();
        try {
            if (value instanceof CharSequence text) return JSON.readValue(text.toString(), new com.fasterxml.jackson.core.type.TypeReference<>() {});
            return JSON.readValue(value.toString(), new com.fasterxml.jackson.core.type.TypeReference<>() {});
        } catch (Exception ignored) { return Map.of(); }
    }

    private static String json(Object value) { try { return JSON.writeValueAsString(value); } catch (Exception e) { return "{}"; } }
    private static String decodeHeaderValue(String value) {
        if (value == null || !value.startsWith("=?base64?") || !value.endsWith("?=")) return value;
        try { return new String(Base64.getDecoder().decode(value.substring(9, value.length() - 2)), StandardCharsets.UTF_8); }
        catch (IllegalArgumentException invalidEncoding) { return "\u0000"; }
    }
    private Object normalize(Object value) { return JSON.convertValue(value, Object.class); }
    private static String safe(Throwable e) { String message = e.getMessage(); if (message == null || message.isBlank()) message = e.getClass().getSimpleName(); return message.length() > 500 ? message.substring(0, 500) : message; }
    private static Set<String> csv(String value) { return value == null || value.isBlank() ? Set.of() : Arrays.stream(value.split(",")).map(String::trim).filter(s -> !s.isEmpty()).collect(java.util.stream.Collectors.toUnmodifiableSet()); }
}
