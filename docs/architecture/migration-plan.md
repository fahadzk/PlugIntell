# Migration and implementation plan

## Principles

- Preserve the Java 21 Maven project, existing plugin SPI, CLI and working RAG execution behavior.
- Keep the platform as a modular monolith while the control plane and execution contracts evolve.
- Keep the REST API, MCP adapter and Studio on shared application services.
- Keep published pipeline snapshots immutable and bind execution to the exact published version.
- Treat in-process plugin JARs as trusted operator-installed code; classloaders are not a security boundary.
- Keep secrets as references and redact resolved values from persisted execution events.

## Delivered increments

1. **Repository analysis:** documented existing modules, target architecture, compatibility notes and migration strategy.
2. **Pipeline foundation:** typed ingestion/query graph definitions, plugin descriptors and config metadata, graph validation, SQLite draft/version storage and publish snapshots.
3. **Execution and ingestion:** plugin adapters, file-root validation and upload, preview, ingestion/query jobs, persisted events, cancellation, node retries/timeouts and safe output summaries.
4. **Control plane and Studio:** pipeline/plugin/execution REST resources, OpenAPI, schema-driven editor, import/export, catalog reload, chunk preview, execution monitor and query playground.
5. **Security:** optional bearer authentication, SQLite account/session storage, role checks, account lifecycle safeguards, audit trail and API secret redaction.
6. **MCP:** shared-service tools/resources/prompts, bearer authentication, modern stateless `2026-07-28` discovery/catalog/call handling, plus `2025-11-25` handshake compatibility.

## Remaining increments

1. **Graph runtime:** named ports, conditional routes and checkpoint/recovery semantics; current parallel-safe stages and fan-in remain bounded by the existing contracts.
2. **Persistence:** PostgreSQL profile and schema migrations; validate retention, recovery and deployment backups.
3. **Query productization:** streaming, retrieval evaluation and version comparison.
4. **Connectors:** add provider integrations with declared capabilities and contract tests, prioritizing what can reuse current plugins.
5. **MCP completion:** run the protocol conformance suite; implement modern subscriptions and SSE only when the runtime supports cancellation/backpressure; add OAuth/per-user authorization.
6. **Production hardening:** rate limits, tenant isolation, OpenTelemetry export, quotas, deployment examples and isolated external plugin execution.
7. **Frontend evolution:** evaluate a React/TypeScript migration against the stable API; retain current working browser Studio until the replacement has feature parity.

## Verification

Use Java 21 for Maven builds. Run `mvn -pl pipeline-builder-api -am test` for control-plane changes and the full reactor test suite before packaging. The Studio JavaScript must pass `node --check`; verify its schema loading and API error states when editing frontend behavior. Do not claim support for a provider or MCP capability until its contract tests pass.
