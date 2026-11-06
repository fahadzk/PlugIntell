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
| `pipeline-builder-ui` | Static dual-workflow Pipeline Studio and project-build page. Backend serves these assets. |
| `plugins/*` | Built-in document parsing/chunking, Ollama embedding/answer, Neo4j indexing/retrieval, merge, and test plugins. |

The root `pom.xml` defines the Maven reactor. This repository does not use Spring Boot, PostgreSQL, Flyway, or a separate frontend build tool as part of the current application.

## Plugin contract and runtime

Each plugin JAR includes `META-INF/PluginManifest.yaml`. The manifest declares plugin identity, API version, Java entrypoint, one logical input type, one logical output type, capabilities, and configuration schema. The catalog scans local plugin JARs and exposes descriptors and JSON Schema to the UI/API.

The standalone `pipeline-core` CLI accepts plugin JAR paths in order. It validates manifest/runtime agreement and the complete adjacent type chain before executing the chain. The Studio control plane adapts the same plugin implementations into graph nodes. Node configurations are validated from manifest schema, and draft connections may reference an editable reusable profile whose plugin-specific settings are merged with node overrides. Publishing persists the merged configuration with live profile references removed, for both workflow lanes. Secret references remain unresolved strings until execution.

Plugins execute in the application JVM. The CLI uses per-plugin class loaders to reduce class conflicts; that boundary is not a security sandbox. Only trusted plugin code should be installed. Plugin instances are created for executions; concurrency is permitted only when the node's plugin declares and implements the parallel-safe contract.

## Pipeline model and execution

The Studio stores schema-version-2 `RAG_PROJECT` definitions containing `DOCUMENT_INGESTION` and `QUERY_PROCESSING` workflow graphs. Each lane has its own nodes, edges, plugin pins, node configuration, and execution policy. Schema-version-1 single-workflow definitions remain readable and executable; Studio saves them into the matching lane of the new project model. A project may have one empty lane. Cross-lane edges are rejected. Catalog descriptors expose explicit `plugin.workflows` manifest metadata, with a both-workflows fallback for older manifests. Draft revisions use optimistic concurrency. Publishing creates an immutable version snapshot; executions refer to that exact version.

The graph validator checks node identities, plugin availability and version compatibility, edge endpoints, type compatibility, cardinality/fan-in rules, and cycles, then returns a deterministic topological order. The executor supports a single root, sequential execution by default, fan-in for plugins that implement the declared contract, and bounded parallel execution for ready nodes only when all nodes in that layer are marked parallel-safe. Per-node retries, exponential backoff, and timeouts are configurable. Cancellation and persisted execution events are available through the control plane.

Ingestion and query are independently executable workflows inside one saved and published project. Typical examples are:

```text
Ingestion: source/file → parser → chunker → embedder → vector writer
Query:     query → retrieval → answer generation
```

The available built-in plugins determine which concrete stages can be composed. Do not infer a provider or graph behavior merely from these conceptual flows.

## Persistence and APIs

SQLite is the implemented metadata store for pipeline drafts/versions, connection profiles, execution jobs/events, and optional accounts/sessions and audit events. The default database path is `./data/plugintell.db`; `PLUGINTELL_DB_PATH` overrides it. Source documents, vector data, and provider data remain in their configured storage systems rather than the control-plane database.

The Micronaut service exposes versioned `/api/v1` routes for plugin, connection, pipeline, execution, ingestion, query, authentication, and audit operations. The generated OpenAPI document is served by the application. REST, MCP, and UI operations call shared application services; MCP is not a second execution engine.

Optional control-plane auth is enabled with `CONTROL_PLANE_AUTH_ENABLED=true`. Accounts have ADMIN, EDITOR, OPERATOR, or VIEWER roles. Secret-valued plugin options use environment references such as `secret://env/VARIABLE_NAME`; returned profile/configuration models redact secrets. The process must actually have the referenced variable set at runtime.

## Connection profile snapshots

`PipelineControlService.publish` expands live profile settings once, validates the resulting configuration, and passes that exact definition to the transactional store publish operation. The editable draft retains its references. Profile editing, draft writes, and publishing serialize through the shared profile service so a publication captures a coherent revision of its settings. Execution and project generation read frozen published definitions and no longer depend on profiles remaining present.

For older published records that still contain `connectionRef`, the store keeps their original `pipeline_versions.definition_json` unchanged. A `published_connection_snapshots` companion row stores the expanded definition and takes precedence on version reads. Historical settings are captured on first version access, or before a referenced profile is edited or deleted. Capturing a set of legacy versions is transactional and idempotent. It does not require the original plugin version to remain installed; normal execution compatibility validation still applies. Deleting a profile remains blocked while any current draft references it.

Snapshots capture configuration and environment variable references, never resolved secret values. Runtime credential rotation through the same environment variable remains possible.

## MCP boundary

The MCP endpoint is hosted by the same Micronaut application at `/mcp`. Its adapter exposes a limited catalog of pipeline/plugin resources and query/ingestion tools through shared services. Authentication uses the configured bearer token when RBAC is disabled or a control-plane session when RBAC is enabled. The implementation is a local adapter rather than the official Java MCP SDK and should not be described as fully conformant until the official conformance suite is run.

See [MCP documentation](docs/mcp/README.md) for supported protocol behavior and current limitations.

## Project generation is separate from pipeline publishing

Studio's **Create project** opens `/ui/project-build.html` in a separate tab. `POST /api/v1/projects/builds` starts an asynchronous `ProjectBuildManager` job from a published version. It expands profile configuration, validates each nonempty lane as a connected linear chain, writes a Maven project into an absolute empty/absent directory on the backend machine, and invokes `MavenProjectBuilder`. Destination symlinks, junctions, and linked ancestors are rejected. Branching, fan-in, cyclic/disconnected graphs and repeated plugin IDs within a lane cannot be exported.

Status and capped logs are polled at `GET /api/v1/projects/builds/{id}`. Progress is a monotonic estimate derived from Maven output. Jobs live in memory without expiry, persistence, or restart recovery; generated directories remain on disk. The bundle endpoint returns the generated project ZIP with `target/` and `lib/`; JARs need the relative libraries, so Studio offers the ZIP for JAR builds. The artifact endpoint serves the built JAR or platform-specific native executable. Native builds require GraalVM Native Image, a host compiler toolchain, and plugin reachability support.

With auth disabled, job creation requires a loopback bind address. With auth enabled, ADMIN/EDITOR can create jobs; all roles can read jobs and downloads. This grants trusted designers filesystem write and Maven execution capability as the backend OS user. Environment scripts omit sensitive values, and API logs/errors redact known values from secret-named environment variables. Raw Maven logs on disk may contain tool output and should be treated as private. Builds are separate from graph execution jobs and do not inherit graph retries/timeouts.

Generated applications contain a `GeneratedPipelineMain` plus `IngestionPipeline` and/or `QueryPipeline`, selected by `--mode ingestion --input <file>` or `--mode query --query <question>`. Typed node configuration is embedded independently; secret environment references resolve at runtime. Configuration and execution reuse the plugin SPI and core validator/runtime. Generated launchers validate ingestion file readability before provider configuration/preflight. Runtime configuration errors include plugin context and the cause; missing secret references identify the environment variable. `OLLAMA_TIMEOUT_SECONDS` overrides the embedded timeout on generated Ollama plugin settings. `--debug` lists environment variables and emits a stack trace, with values for referenced secrets and variables whose names look sensitive redacted. These launchers do not implement the control plane's graph retries/timeouts. The legacy UI and `/api/plugins`, `/api/pipelines/validate`, and `/api/pipelines/generate` routes are removed; `/` redirects to Studio.

`ExecutionRequest.workflow` selects a lane for `/api/v1/executions`; ingestion/query endpoints select the lane themselves and reject conflicting flags. Execution records persist the selected workflow in an additive nullable SQLite column; historical records retain their original pipeline type through the version snapshot.

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

