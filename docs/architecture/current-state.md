# Current state

This document describes code that exists in the repository today. For proposed future work, see [target state](target-state.md) and [gap analysis](gap-analysis.md).

## Project and build

The canonical project root is the directory containing the root Maven `pom.xml`. It is a Java 21 Maven reactor. The REST application uses Micronaut. The UI is static HTML/CSS/JavaScript served from the application JAR. It does not use Spring Boot, React, Vite, or a separate Node development server.

The repository contains:

- `pipeline-api`: plugin contracts and shared RAG models.
- `pipeline-core`: standalone plugin loader, linear-chain runtime, validation and RAG CLI.
- `pipeline-builder-api`: Micronaut control plane, persistence, graph execution, REST, optional auth/audit, and MCP adapter.
- `pipeline-builder-ui`: Studio and legacy project generator assets.
- `plugins/`: built-in and test plugin Maven modules.

`build.bat` runs the Maven package build with tests skipped. `run.bat` starts the packaged backend in another console and opens `/ui/studio.html`. `run-backend.bat` starts only the backend. Maven and Java installations may be selected with environment variables described in the root README and operations guide.

## Control plane and Studio

The application stores pipeline drafts and published snapshots in SQLite, supports optimistic draft revisions, and creates immutable published versions. Pipeline definitions pin plugin versions and contain typed nodes, edges, configuration, and execution policy.

The plugin catalog reads local plugin JAR manifests and provides plugin descriptors, capabilities, ports, and configuration JSON Schema. Studio configuration forms are generated from these schemas. Reusable connection profiles may contain settings for multiple plugins; pipelines store profile references and node-level overrides. Secret values are represented by environment references and resolved in the backend process.

The Studio supports pipeline CRUD, import/export, graph validation, publishing, ingestion/query execution, execution trace inspection, supported chunk previews, and profile management. The legacy page at `/` accepts a compatible ordered plugin list and builds a downloadable generated-project ZIP synchronously. Publishing a Studio pipeline does not create a project.

## Execution and integrations

The graph runtime validates a directed acyclic graph and executes one-root pipelines. Stages are sequential by default. Explicit fan-in plugins may accept multiple inputs; parallel execution requires each node in a ready layer to declare and implement the parallel-safe contract. Per-node retries, backoff, timeouts, cancellation, and persisted execution events are supported. Execution state and events are stored in SQLite; interrupted jobs are not resumed after a process restart.

Built-in plugin modules include file/PDF parsing, chunking, Ollama embedding and answer generation, Neo4j vector writing and retrieval, document merge, and deterministic test plugins. Live OCR/model/database execution still requires corresponding external software/services and configuration.

## Security and operations

The server binds to loopback by default. Optional SQLite-backed bearer authentication and role checks use ADMIN, EDITOR, OPERATOR, and VIEWER roles. Audit records are available for supported actions. The MCP endpoint applies the configured bearer/RBAC model and routes operations through shared services.

The ingestion API restricts local file access to `PIPELINE_INPUT_ROOT`. Secret configuration fields accept `secret://env/NAME` references; the environment variable must exist in the process environment. Profile APIs redact secrets. Plugin code is trusted in-process Java code; class loading does not provide a sandbox.

SQLite is the only implemented control-plane database. Tenant isolation, deployment-grade key management, external secret-manager integration, durable checkpoints/recovery, scheduling, SSE query output, retrieval evaluation, and full MCP conformance validation are not implemented.

