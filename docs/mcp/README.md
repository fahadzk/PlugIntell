# MCP interface

The Micronaut service exposes a bearer-authenticated Streamable HTTP endpoint at `/mcp`. It supports the stateless MCP protocol revision `2026-07-28` and retains handshake-era compatibility for `2025-11-25` clients. Both interfaces call the same application services as REST. The modern Java MCP SDK API is still preview, so the current adapter is locally implemented and covered by controller contract tests.

## Enable

When control-plane RBAC is disabled, set `MCP_BEARER_TOKEN` before starting the application. Without either RBAC or this token, `/mcp` returns 503. When RBAC is enabled, users authenticate with their normal control-plane session token and MCP applies the same role rules to read, query and ingestion tools. Clients send `Authorization: Bearer <token>` and `Accept: application/json, text/event-stream`. The service binds to loopback by default. Add exact comma-separated values to `MCP_ALLOWED_ORIGINS` and `MCP_ALLOWED_HOSTS` only when using a trusted browser origin or reverse proxy.

### Current stateless clients

Every JSON-RPC request is a new POST with `MCP-Protocol-Version: 2026-07-28`, `Mcp-Method`, and the matching request metadata in `params._meta`. The metadata includes `io.modelcontextprotocol/protocolVersion`, `io.modelcontextprotocol/clientInfo`, and `io.modelcontextprotocol/clientCapabilities`. Calls to `tools/call`, `resources/read`, and `prompts/get` also require `Mcp-Name` matching `params.name` or `params.uri`. Base64-sentinel encoded names are decoded before comparison.

Use `server/discover` to retrieve supported versions, capabilities, and server identity. Modern clients do not receive or send a protocol session ID. GET is disabled. JSON responses include the required `resultType: complete`; discovery and catalog responses have conservative private, zero-duration cache hints.

### Legacy session clients

Legacy clients send `initialize` with protocol version `2025-11-25`, retain the returned `Mcp-Session-Id`, and send `MCP-Protocol-Version: 2025-11-25` on later requests. This compatibility mode is stateful and independent of the modern stateless request path.

## Tools

- `rag_list_pipelines`, `rag_get_pipeline`, `rag_validate_pipeline`
- `rag_list_plugins`, `rag_get_plugin_schema`
- `rag_execute_query`, `rag_start_ingestion`
- `rag_get_execution_status`, `rag_get_execution_trace`

Tool execution calls the same pipeline services as REST. Query/ingestion calls return an execution ID; use the status and trace tools to poll it. Ingestion paths are confined to `PIPELINE_INPUT_ROOT`. Secret references are identifiers only; configured environment variables are resolved inside the execution process.

## Resources and prompts

Resources expose pipeline definitions and plugin descriptors under `plugintell://` URIs. Prompts include query analysis, pipeline troubleshooting, and retrieval evaluation. The MCP server does not expose raw document text.

## Current boundaries

The adapter supports stateless discovery and core tools, resources, and prompts, plus the legacy 2025 handshake. It does not yet support `subscriptions/listen`, SSE streaming responses, pagination cursors, OAuth discovery, sampling, elicitation, or MRTR. With RBAC enabled, control-plane users are authorized per MCP tool; otherwise the endpoint uses one configured shared bearer token. Pipeline execution remains bounded by the graph executor: one root, sequential stages by default, parallel stages only for explicitly safe plugins, and opt-in fan-in plugins. Run the official MCP conformance suite before exposing the endpoint beyond a trusted local network.
