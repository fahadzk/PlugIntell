# Current state

This document describes code that exists in the repository today. For proposed future work, see [target state](target-state.md) and [gap analysis](gap-analysis.md).

## Project and build

Provider plugins and the optional Micronaut host share the root Netty BOM. Studio rejects conflicting Netty artifact versions embedded in shaded selected libraries before compilation; Python-generated web projects import the same BOM. Tika-based document splitting includes Jakarta Mail signatures required by its native activation feature. Native document/image builds can emit DLL/shared-library sidecars, which must remain beside the executable; the complete project ZIP preserves them.

The canonical project root is the directory containing the root Maven `pom.xml`. It is a Java 21 Maven reactor. The REST application uses Micronaut. The UI is static HTML/CSS/JavaScript served from the application JAR. It does not use Spring Boot, React, Vite, or a separate Node development server.

The repository contains:

- `pipeline-api`: plugin contracts and shared RAG models.
- `pipeline-core`: standalone plugin loader, linear-chain runtime, validation and RAG CLI.
- `pipeline-builder-api`: Micronaut control plane, persistence, graph execution, REST, optional auth/audit, and MCP adapter.
- `pipeline-builder-ui`: Studio and project-build assets.
- `plugins/`: built-in and test plugin Maven modules.

`build.bat` runs the Maven package build with tests skipped. `run.bat` starts the packaged backend in another console and opens `/ui/studio.html`. `run-backend.bat` starts only the backend. Maven and Java installations may be selected with environment variables described in the root README and operations guide.

## Control plane and Studio

The application stores pipeline drafts and published snapshots in SQLite, supports optimistic draft revisions, and creates immutable published versions. Pipeline definitions pin plugin versions and contain typed nodes, edges, configuration, and execution policy.

The plugin catalog reads local plugin JAR manifests and provides plugin descriptors, capabilities, ports, and configuration JSON Schema. Studio configuration forms are generated from these schemas. Reusable connection profiles may contain settings for multiple plugins; drafts store live profile references and node-level overrides. Profiles remain editable. Publishing snapshots the merged configuration independently for both lanes, removes live profile references, and leaves drafts linked to the latest profile settings. Older published versions use persistent companion snapshots created on first access or before profile mutation, preserving their original stored JSON. Deleting a profile is blocked only while a current draft references it. Secret values are represented by environment references and resolved in the backend process.

The Studio supports pipeline CRUD, import/export, graph validation, publishing, ingestion/query execution, execution trace inspection, supported chunk previews, and profile management. The former generator page and unversioned generator endpoints have been removed; `/` redirects to Studio. Publishing a Studio pipeline does not create a project.

Studio also has **Create project**, which opens a separate tab for selecting a published version, an absolute empty/absent folder on the backend machine, and JAR or native output. Each nonempty lane must be a connected linear graph; branching, fan-in, cycles, disconnected graphs, and repeated plugin IDs within a lane are unsupported. Symlinks/junctions in destination paths are rejected. Polling shows estimated progress and the last 2,000 log lines. Jobs have no expiry and live only in memory: restart loses their status/download URLs, while project files survive. Downloads include a ZIP because the runnable JAR requires the relative `lib/` directory. Native builds require GraalVM and the host compiler toolchain; compatibility depends on plugin reachability metadata. See [operations](../operations/local-development.md) for running and security details.

Studio edits schema-version-2 `RAG_PROJECT` definitions with two stacked lanes: ingestion above query. Each lane has its own graph and Run action. All/Ingestion/Query filtering uses the plugin YAML `workflows` list and combines with search. Saving/publishing versions both lanes together. Either lane can be empty; at least one must have nodes. Existing schema-version-1 pipelines remain readable and run unchanged, and the Studio upgrades drafts on save. Generated JAR/native applications select the workflow using `--mode ingestion --input <file>` or `--mode query --query <question>`, with independent typed configuration and runtime secret resolution. Execution history persists the chosen workflow.

## Execution and integrations

The graph runtime validates a directed acyclic graph and executes one-root pipelines. Stages are sequential by default. Explicit fan-in plugins may accept multiple inputs; parallel execution requires each node in a ready layer to declare and implement the parallel-safe contract. Per-node retries, backoff, timeouts, cancellation, and persisted execution events are supported. Execution state and events are stored in SQLite; interrupted jobs are not resumed after a process restart.

Built-in plugin modules include file/PDF parsing, chunking, Ollama embedding and answer generation, Neo4j vector writing and retrieval, document merge, and deterministic test plugins. Live OCR/model/database execution still requires corresponding external software/services and configuration.

## Structured reporting and monitoring

Both runtimes emit shared version-1 envelopes with run/node/attempt identity and per-run cursors. Built-ins expose inherited domain/progress helpers and reporting constructors. Runtime wrappers own authoritative lifecycle, real handoffs, retries/timeouts, cancellation requests and termination. Immutable per-attempt reporters reject/count obsolete messages. Adapter workers isolate bounded delivery and report drops/coalescing/failures.

Studio persists separate adapter definitions/subscriptions, host selection and template pins in drafts/published snapshots. The static UI supplies a UI templates catalog beside Adapters, Aurora Studio and Midnight Observatory external ZIP themes, typed settings/isolated previews, standard/water live monitors, inspectors, timelines, safe I/O summaries, filtered domain views, reconnect/gap handling, and sandboxed version-1 custom template bundles. Existing polling remains compatible. Structured monitoring uses additive SQLite tables; standalone generated monitoring is memory-only. Selected web applications support serve and repeated ingestion/query runs; minimal CLI output excludes that host. Both generators emit direct assembly. Kafka requires the optional producer build and reachable broker; Micronaut web/UI native generation is supported, while Kafka native generation remains unsupported. Selected processing plugins require their own native-compatible dependencies and reachability metadata.

Generated web applications display final `AnswerResult`/`String` query outputs and citation metadata through a separate no-store result endpoint. The bounded in-memory result projection excludes retrieved passages and stays out of telemetry, snapshots, custom templates and structured logs; restart or run eviction removes it.

See [events and generated applications](../operations/events-and-generated-apps.md) for exact configuration, delivery/retention bounds, supported packaging and limitations.

## Security and operations

The server binds to loopback by default. Optional SQLite-backed bearer authentication and role checks use ADMIN, EDITOR, OPERATOR, and VIEWER roles. Audit records are available for supported actions. The MCP endpoint applies the configured bearer/RBAC model and routes operations through shared services.

The ingestion API restricts local file access to `PIPELINE_INPUT_ROOT`. Secret configuration fields accept `secret://env/NAME` references; the environment variable must exist in the process environment. Profile APIs redact secrets. Plugin code is trusted in-process Java code; class loading does not provide a sandbox.

SQLite is the only implemented control-plane database. Tenant isolation, deployment-grade key management, external secret-manager integration, durable checkpoints/recovery, scheduling, SSE query output, retrieval evaluation, and full MCP conformance validation are not implemented.
