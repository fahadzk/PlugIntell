# PlugIntell RAG Pipeline Studio

PlugIntell is a Java 21, Maven multi-module project for composing and running document-ingestion and retrieval-augmented generation (RAG) workflows. It contains a Java plugin runtime, a Micronaut REST control plane, a browser-based Pipeline Studio, and an MCP endpoint.

The project is a modular monolith. The Studio is plain HTML, CSS, and JavaScript served by the backend; it is not a React application. Pipeline configurations and execution history are stored in SQLite by default. Plugins execute as trusted Java code in the application process.

## Quick start on Windows

Open Command Prompt in this directory and run:

```bat
build.bat
run.bat
```

`build.bat` packages the Maven reactor. `run.bat` starts the backend in a separate console and opens <http://127.0.0.1:8080/ui/studio.html>. The same server hosts the frontend and REST APIs. Close the backend console, or press Ctrl+C in it, to stop the application.

Requirements: JDK 21 and Maven 3.9 or newer. If either tool is not on `PATH`, set `JAVA_HOME` and/or `MAVEN_HOME`. Set `MAVEN_REPO_LOCAL` to use a specific local Maven repository. `run.bat` does not set database credentials; define required provider environment variables in the same shell before starting the app.

To run only the backend without opening a browser, use `run-backend.bat` after building. The server listens on `127.0.0.1:8080` by default; `PORT` and `BIND_ADDRESS` can override these values.

## Main capabilities

- Discover locally installed plugins from their JAR manifests.
- Create, edit, validate, import, export, and publish versioned pipeline graphs.
- Configure plugin nodes from manifest-declared JSON Schema and reusable multi-plugin connection profiles.
- Run document-ingestion and query pipelines and inspect persisted execution events.
- Upload supported documents and preview chunking without persisting preview results.
- Serve REST endpoints and an MCP endpoint from shared application services.
- Generate and download a fixed plugin-chain Maven project from the legacy generator at `/`.

The visual Studio is at `/ui/studio.html`. The legacy ordered-chain generator is at `/`. A published pipeline is a versioned configuration snapshot; publishing alone does not create a separate project or executable.

## Repository layout

| Path | Purpose |
| --- | --- |
| `pipeline-api/` | Stable Java plugin contracts and typed document, chunk, embedding, and result models. |
| `pipeline-core/` | Plugin JAR loading, contract validation, CLI execution, and RAG runtime support. |
| `pipeline-builder-api/` | Micronaut REST application, persistence, plugin catalog, execution services, auth, audit, and MCP adapter. |
| `pipeline-builder-ui/` | Static browser UI assets bundled into the backend JAR. |
| `plugins/` | Built-in ingestion, embedding, vector retrieval/storage, and example plugins. |
| `docs/` | Architecture, operations, plugin, MCP, and example documentation. |
| `tools/` | Separate development utilities, including the native launcher generator. |

See [ARCHITECTURE.md](ARCHITECTURE.md) for implemented component boundaries and runtime flows. See [AGENTS.md](AGENTS.md) for repository-specific instructions for coding agents and [CONTRIBUTING.md](CONTRIBUTING.md) for contributor workflow.

## Build and verify

From the repository root, with Java 21 and Maven available:

```powershell
mvn test
mvn package -DskipTests
node --check pipeline-builder-ui/studio.js
```

The Windows `build.bat` script runs the Maven package build with tests skipped. The Maven reactor is declared in the root `pom.xml`; use `mvn -pl pipeline-builder-api -am test` to focus on the control plane and its required modules.

## Local runtime configuration

Useful environment variables include:

| Variable | Default | Purpose |
| --- | --- | --- |
| `PORT` | `8080` | HTTP listener port. |
| `BIND_ADDRESS` | `127.0.0.1` | Bind address. Keep loopback unless deployment security is configured. |
| `PLUGINTELL_DB_PATH` | `./data/plugintell.db` | SQLite control-plane database. |
| `PLUGINTELL_PLUGIN_ROOT` | `./plugins` | Directory scanned for built plugin JARs. |
| `PIPELINE_INPUT_ROOT` | `./data/ingestion` | Allowed root for local ingestion inputs and uploads. |
| `MAVEN_HOME` | Maven on `PATH` | Maven used by the legacy generated-project builder. |
| `MAVEN_REPO_LOCAL` | Maven default | Optional Maven local repository. |
| `PIPELINE_BUILD_TIMEOUT_SECONDS` | `3600` | Legacy generated-project build timeout. |
| `PIPELINE_EXECUTION_MAX_PARALLELISM` | `4` | Per-execution limit for eligible parallel graph nodes. |

Provider credentials belong in environment variables referenced by plugin configuration (for example, `secret://env/NEO4J_PASSWORD`). Do not put secret values in source files, pipeline JSON, connection profiles, or logs. See [local development](docs/operations/local-development.md) for database/provider setup and optional authentication.

## Architecture and current boundaries

The Studio stores typed directed acyclic graphs and publishes immutable versions. Execution supports a single graph root, sequential stages by default, fan-in where the plugin explicitly supports it, and parallel ready stages only when every participating plugin declares and implements parallel safety. Execution jobs, traces, pipeline versions, profiles, users/sessions (when enabled), and audit records use SQLite.

The plugin loader and Studio do not sandbox plugins. PostgreSQL control-plane persistence, tenant isolation, external secret-manager integration, durable checkpoints, query streaming, and an official MCP conformance run are not implemented. The current Studio is dependency-light static JavaScript, not React/React Flow. Check [the gap analysis](docs/architecture/gap-analysis.md) before relying on a capability for deployment.

## Documentation index

- [Architecture](ARCHITECTURE.md)
- [Current state](docs/architecture/current-state.md)
- [Target state and guardrails](docs/architecture/target-state.md)
- [Gap analysis](docs/architecture/gap-analysis.md)
- [Migration plan](docs/architecture/migration-plan.md)
- [Local development](docs/operations/local-development.md)
- [Plugin developer guide](docs/plugins/developer-guide.md)
- [MCP interface](docs/mcp/README.md)
- [Pipeline examples](docs/examples/README.md)
- [Legacy generated-project guide](GENERATOR.md)
- [GraalVM Native Image guide](NATIVE-IMAGE.md)

