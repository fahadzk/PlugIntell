# Architecture

## System shape

PlugIntell is a Java 21 Maven multi-module modular monolith. The Micronaut application is both the local control plane and the runtime host for pipeline execution. The browser UI, REST API, MCP adapter, and CLI reuse the Java plugin contracts and execution services where their use cases overlap.

```text
Browser Studio ── REST ─┐
MCP clients ───── /mcp ─┼── Micronaut application ── SQLite metadata store
                        │         ├── Plugin catalog / validation
                        │         ├── Pipeline control and execution
                        │         └── Trusted plugin implementations
CLI ────────────────────┴── pipeline-core + plugin JARs
```

The Studio is static HTML/CSS/JavaScript packaged into the backend JAR. It edits persisted pipeline graphs and calls the versioned REST API. It is not currently implemented with React or React Flow.

## Modules

| Module | Implemented responsibility |
| --- | --- |
| `pipeline-api` | `Plugin` SPI, manifests, configuration/lifecycle/preflight/fan-in/parallel-safety contracts, and shared RAG data types. |
| `pipeline-core` | Manifest reading, plugin class loading, linear CLI validation/execution, and RAG CLI flows. |
| `pipeline-builder-api` | Micronaut HTTP application, plugin discovery, graph and plugin validation, SQLite stores, execution jobs, connection profiles, optional authentication/RBAC, audit, and MCP adapter. |
| `pipeline-builder-ui` | Static Pipeline Studio and legacy ordered-chain generator pages. Backend serves these assets. |
| `plugins/*` | Built-in document parsing/chunking, Ollama embedding/answer, Neo4j indexing/retrieval, merge, and test plugins. |

The root `pom.xml` defines the Maven reactor. This repository does not use Spring Boot, PostgreSQL, Flyway, or a separate frontend build tool as part of the current application.

## Plugin contract and runtime

Each plugin JAR includes `META-INF/PluginManifest.yaml`. The manifest declares plugin identity, API version, Java entrypoint, one logical input type, one logical output type, capabilities, and configuration schema. The catalog scans local plugin JARs and exposes descriptors and JSON Schema to the UI/API.

The standalone `pipeline-core` CLI accepts plugin JAR paths in order. It validates manifest/runtime agreement and the complete adjacent type chain before executing the chain. The Studio control plane adapts the same plugin implementations into graph nodes. Node configurations are validated from manifest schema, and connections may reference a reusable profile whose plugin-specific settings are merged with node overrides.

Plugins execute in the application JVM. The CLI uses per-plugin class loaders to reduce class conflicts; that boundary is not a security sandbox. Only trusted plugin code should be installed. Plugin instances are created for executions; concurrency is permitted only when the node's plugin declares and implements the parallel-safe contract.

## Pipeline model and execution

The Studio stores `DOCUMENT_INGESTION` and `QUERY_PROCESSING` pipeline definitions with schema version, nodes, edges, plugin ID/version pins, node configuration, and execution policy. Draft revisions use optimistic concurrency. Publishing creates an immutable version snapshot; executions refer to that exact version.

The graph validator checks node identities, plugin availability and version compatibility, edge endpoints, type compatibility, cardinality/fan-in rules, and cycles, then returns a deterministic topological order. The executor supports a single root, sequential execution by default, fan-in for plugins that implement the declared contract, and bounded parallel execution for ready nodes only when all nodes in that layer are marked parallel-safe. Per-node retries, exponential backoff, and timeouts are configurable. Cancellation and persisted execution events are available through the control plane.

Ingestion and query pipelines are independent workflows. Typical examples are:

```text
Ingestion: source/file → parser → chunker → embedder → vector writer
Query:     query → retrieval → answer generation
```

The available built-in plugins determine which concrete stages can be composed. Do not infer a provider or graph behavior merely from these conceptual flows.

## Persistence and APIs

SQLite is the implemented metadata store for pipeline drafts/versions, connection profiles, execution jobs/events, and optional accounts/sessions and audit events. The default database path is `./data/plugintell.db`; `PLUGINTELL_DB_PATH` overrides it. Source documents, vector data, and provider data remain in their configured storage systems rather than the control-plane database.

The Micronaut service exposes legacy routes for the ordered-chain generator and versioned `/api/v1` routes for plugin, connection, pipeline, execution, ingestion, query, authentication, and audit operations. The generated OpenAPI document is served by the application. REST, MCP, and UI operations call shared application services; MCP is not a second execution engine.

Optional control-plane auth is enabled with `CONTROL_PLANE_AUTH_ENABLED=true`. Accounts have ADMIN, EDITOR, OPERATOR, or VIEWER roles. Secret-valued plugin options use environment references such as `secret://env/VARIABLE_NAME`; returned profile/configuration models redact secrets. The process must actually have the referenced variable set at runtime.

## MCP boundary

The MCP endpoint is hosted by the same Micronaut application at `/mcp`. Its adapter exposes a limited catalog of pipeline/plugin resources and query/ingestion tools through shared services. Authentication uses the configured bearer token when RBAC is disabled or a control-plane session when RBAC is enabled. The implementation is a local adapter rather than the official Java MCP SDK and should not be described as fully conformant until the official conformance suite is run.

See [MCP documentation](docs/mcp/README.md) for supported protocol behavior and current limitations.

## Project generation is separate from pipeline publishing

The legacy generator accepts an ordered list of compatible plugin IDs and creates a Maven launcher project with plugin JARs. Its current `/api/pipelines/generate` endpoint builds a bundle synchronously for download. It is distinct from the graph control plane: saving or publishing a Studio pipeline stores configuration and does not create a project or executable.

The repository also has a separate Python utility for generating a fixed-plugin native launcher; see [GENERATOR.md](GENERATOR.md) and [NATIVE-IMAGE.md](NATIVE-IMAGE.md). GraalVM native images are platform-specific and cannot load arbitrary plugins after image creation.

## Operational and security boundaries

- The service binds to loopback by default. Do not expose it on a shared network without authentication, TLS, and a trusted proxy.
- The ingestion API constrains input paths to `PIPELINE_INPUT_ROOT`.
- Plugins are trusted in-process Java code; third-party JARs are not isolated.
- Optional RBAC and audit are not equivalent to tenant isolation. Multi-tenancy is not implemented.
- Provider calls should be configured with finite timeouts. Keep secret values out of pipeline exports and logs.
- SQLite is the current persistence option. PostgreSQL support and a migration framework are not implemented.
- Durable checkpoints, scheduler/worker recovery, SSE, retrieval evaluation/version comparison, and external secret management remain future work.

For the implementation inventory and tested boundaries, see [current state](docs/architecture/current-state.md) and [gap analysis](docs/architecture/gap-analysis.md).

