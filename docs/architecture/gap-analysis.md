# Gap analysis

## Implemented foundation

| Capability | Current implementation | Remaining work |
|---|---|---|
| Java/plugin base | Java 21 Maven modules and existing Java SPI preserved; manifest-backed plugin catalog, schemas, ports, capabilities, config validation, lifecycle wrappers | Isolated runtime for untrusted plugins; broader native provider integrations |
| Pipeline model | Versioned typed graph DTOs, DAG/cycle/type validation, node policies, opt-in fan-in contract | Named ports, conditional routes, graph-level policies |
| Pipeline persistence | SQLite drafts, optimistic revision checks, immutable published versions | PostgreSQL deployment option, migrations and backup/retention operations |
| Execution | Persisted jobs/events with sequence-cursor trace reads, cancellation, output redaction, node timeout/retries, sequential fan-in, and opted-in parallel stages with a per-execution bound | SSE streaming, checkpoints, scheduling, durable worker recovery and global resource quotas |
| Ingestion | Existing plugin adapters, file upload/root validation, preview, ingest execution and job monitoring | Additional source connectors, sync/deletion workflows, full document lineage and ACL propagation |
| Query | Published query pipeline execution, configurable plugin graph, retrieval traces and query UI | Streaming output, evaluation datasets, pipeline compare, token/cost reporting |
| REST API | Versioned Micronaut APIs, OpenAPI, auth/RBAC, audit, plugin/execution operations, reusable SQLite connection profiles | Rate limiting, tenant-aware authorization, idempotency keys, broad REST integration coverage |
| UI | Browser Studio for pipeline CRUD/import/export, draggable positioned graph canvas with SVG edges, dynamic schema forms, connection profile CRUD/testing, execution monitoring, chunk preview, query playground | React/TypeScript migration and accessibility/E2E hardening; current UI remains dependency-light static JavaScript |
| MCP | Bearer-authenticated adapter supports stateless 2026-07-28 discovery and core catalogs/calls plus 2025-11-25 compatibility | Full conformance suite, subscriptions/SSE, pagination, OAuth and per-user authorization; use official SDK when its modern API is stable |
| Operations | Local packaging, health endpoints, database-backed execution/audit records and docs | Production deployment topology, OpenTelemetry export, metrics dashboards and plugin isolation |

## Key compatibility limits

1. Legacy plugins declare one input and output type; graph edges do not yet select named ports.
2. Plugins execute in-process. Installed plugin JARs must be trusted; classloader separation is not a sandbox.
3. The Studio is currently static JavaScript rather than React/TypeScript, but consumes backend schema metadata and contracts.
4. SQLite is the current control-plane store. PostgreSQL and Flyway/Liquibase migrations are not wired into the application.
5. MCP modern protocol handling is implemented in a local adapter, not the MCP Java SDK. Modern clients are stateless; legacy 2025 sessions are held in-process.
6. Execution has one root. Fan-in requires both a manifest capability and runtime interface. Parallel execution requires every node in a ready layer to declare and implement the parallel-safe marker; configured retries are opt-in and should only be used for idempotent plugin operations.

## Next implementation priorities

1. Expand graph contracts to named typed ports and conditional routes.
2. Add durable worker recovery/checkpoints and resource-aware execution controls.
3. Add PostgreSQL persistence profile with migrations and test the same repository contracts against SQLite and PostgreSQL.
4. Add streaming query execution, test datasets and version comparison.
5. Add connector implementations incrementally, driven by existing provider integrations and dependency availability.
6. Run official MCP conformance checks and implement modern subscriptions/streaming before advertising those capabilities.
