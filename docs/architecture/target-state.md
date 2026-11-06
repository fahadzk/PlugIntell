# Target state

Evolve the project as a modular monolith. The existing Java plugin runtime and RAG integrations remain the execution plane; the Micronaut control plane stores and validates versioned ingestion/query DAGs and serves REST, MCP, and the Studio. All interfaces call shared application services.

## Logical boundaries

- **Plugin API/runtime:** stable plugin metadata, configuration schema, typed ports, discovery, health/lifecycle, and trusted plugin loading. Preserve the existing manifest and `Plugin` API through adapters.
- **Pipeline model/validation:** schema-versioned definitions, plugin-pinned nodes, typed edges, DAG validation, deterministic topological order, and immutable published snapshots.
- **Execution:** the executor adapts synchronous plugins, records execution IDs/events, supports cancellation, bounded retries/timeouts, opt-in parallel-safe stages, and fan-in contracts. Checkpointing still requires a durable plugin/runtime contract.
- **Control plane:** the Micronaut service provides SQLite persistence, optimistic revisions, secret references, pipeline/plugin/execution APIs, optional SQLite-backed authentication/RBAC, account lifecycle safeguards, and mutation audit records. Add tenant isolation, external secret management and deployment-grade key management before remote deployment.
- **Studio UI:** the existing UI uses schema-driven forms and a graph editor. A React/React Flow migration remains optional and should follow only if it improves interaction quality.
- **MCP:** the adapter calls shared application services, supports stateless protocol revision 2026-07-28 and retains 2025-11-25 handshake compatibility. When control-plane RBAC is enabled it authenticates user sessions and applies roles to query/ingestion tools. Validate against the official conformance suite before remote deployment.

## Implemented foundation

The builder has a schema-versioned graph model, typed DAG validation, manifest-derived plugin descriptors/config schemas, SQLite draft/version storage, immutable published snapshots, asynchronous execution with opt-in parallel stages and fan-in, persisted execution events, file-root constrained ingestion, schema-driven Studio forms, in-memory chunk previews, optional account/RBAC and audit records, and a local MCP adapter. Validation checks node IDs, plugin references and pinned versions, edge endpoints, current single input/output type contracts, fan-in declarations, duplicate/self edges, and cycles. Successful validation returns a stable topological order. The legacy ordered-chain validation and generated-project endpoints remain available.

## Guardrails and remaining work

The current executor supports single-root acyclic graphs, sequential defaults, parallel ready layers when each plugin explicitly declares thread safety, and explicit plugin fan-in. It does not support durable checkpoints. REST authentication is optional, tenant isolation is not implemented, and MCP conformance testing remains outstanding. Keep the server on loopback or behind an authenticated proxy. Keep the old pipeline path operational while extending the new capabilities behind versioned APIs.
