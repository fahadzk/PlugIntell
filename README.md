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
- Run document-ingestion and query pipelines and inspect persisted execution events, live SSE monitoring, and configurable built-in/external UI templates.
- Attach optional local UI and Kafka telemetry destinations to nodes/workflows; select an optional Micronaut host for generated web applications.
- Upload supported documents and preview chunking without persisting preview results.
- Serve REST endpoints and an MCP endpoint from shared application services.
- Use **Create project** in Studio to open a separate build tab, create a local Maven project from a published linear pipeline, and follow build progress and logs.

The Studio is at `/ui/studio.html`; `/` redirects there. The legacy generator UI and unversioned generator endpoints have been removed. A published pipeline is a versioned configuration snapshot; publishing alone does not create a separate project or executable.

The existing graph extraction node supports **Ollama, OpenAI, or Gemini** through its `LLM_PROVIDER` selector. See the [provider setup guide](docs/plugins/knowledge-graph.md#choose-the-extraction-provider); API keys use environment-secret references.

## AI Studio

For evidence-backed graph RAG, see [Ollama / Neo4j knowledge graph plugins](docs/plugins/knowledge-graph.md). The graph extraction and read-only graph retrieval stages use the shared repository, scoped evidence, and imported editable example workflows; they are separate from the existing vector RAG path.

Use **AI Studio** in Studio to describe a RAG workflow and get ordered plugin recommendations. Use the result to create an editable workflow in Studio or publish and build an application. Mark a connection profile as default under **Connections** to reuse its settings automatically. See [AI Studio setup and network-hosted Laya testing](docs/operations/ai-studio.md).

## Create a runnable project

Studio shows document ingestion above document query in one project, with no pipeline-type dropdown. Filter the plugin catalog by **All**, **Ingestion**, or **Query**, combined with text search. Plugin manifests declare `plugin.workflows`; plugins without this field remain visible in both filters. Each lane validates and runs independently, sharing connection profiles. Saving and publishing snapshots both lanes together; either lane may be empty, but the project needs at least one workflow. Existing schema-version-1 pipelines load into their matching lane and become schema-version-2 RAG projects when saved. Published historical versions remain unchanged.

Click **Create project** in Studio, select a published project, enter a project name and an absolute destination on the **backend machine**, and choose Simple JAR or GraalVM native executable. The folder must be empty or absent; symbolic links and junctions in the destination or its parents are rejected. Each nonempty lane must be a connected linear graph, with each plugin used once per lane; branching, fan-in, cycles, and disconnected graphs are rejected. Configuration is embedded separately for each node and workflow. Secret references remain references and resolve locally at runtime; set their named environment variables before running.

Maven must be available to the backend. The separate tab polls status and Maven logs. For JAR builds, download and extract the **project ZIP**, then run `java -jar target/<project-name-in-lowercase>-1.0.0.jar --mode ingestion --input <input-file>` from the extracted project folder (use `--mode query --query "Your question"` for the query workflow). Keep `lib/` beside `target/`: the JAR uses relative library paths and is not standalone. Java 21 is required. Native builds also require GraalVM Native Image and the host compiler toolchain, and produce a platform-specific executable; plugin reflection may require reachability metadata.

Files remain at the destination after success or failure. Build jobs, progress, and logs are held in backend memory and lost on restart; generated files remain on disk, but old job/download URLs stop working. Jobs have no expiry or recovery, and logs retain the last 2,000 lines. Progress percentages are estimates. Without authentication, builds require a loopback bind address; with authentication, ADMIN and EDITOR may start builds, and all four roles may read status and downloads. Use this filesystem-writing feature only for trusted users.

## Events and generated web applications

Build provider plugins and the web host together when updating dependencies. The reactor aligns their Netty versions, and Studio rejects conflicting versions embedded in selected shaded JARs. If Native Image reports `CleanerJava6Substitution.CLEANER_FIELD_OFFSET`, rebuild the complete reactor, restart Studio, and regenerate into a new empty folder; see [native troubleshooting](NATIVE-IMAGE.md#troubleshooting).

Generated startup scripts list available commands when invoked without arguments. Web-enabled projects include `run.bat serve` and `sh run.sh serve`. The generated README explains both CLI and web modes, input paths and server logs. The Micronaut host shows structured execution logs in its terminal and rotates `logs/server-0.log` across three 5 MB files; `PIPELINE_LOG_DIR` selects another directory. Follow them with `Get-Content .\logs\server-0.log -Wait -Tail 100` in PowerShell. Generated query runs also display the actual answer and citation metadata in **Query answer**; open an execution from history to restore its output. Results are kept in memory within the run retention limits and remain separate from telemetry/logs. Regenerate older downloads to receive updated scripts, UI assets and host libraries.

Use **Adapters** / **Monitoring** to attach event destinations, choose the generated host, and select a template. The default build excludes Kafka client dependencies and marks that adapter unavailable; build with `mvn -Pkafka clean package` to enable its producer. Web-enabled generated JARs also support `serve`, keep serving across runs, and bind to loopback. CLI output has no server dependency or serve command. Micronaut web/UI projects can also generate a GraalVM native executable: select the native build target and use `run.bat serve` after extracting its ZIP. Selected UI/template resources are embedded; Java is needed for building, not for running the executable. Kafka native combinations remain rejected. Selected processing plugins still require compatible native dependencies and reachability metadata.

Open **UI templates** beside **Adapters** to preview Aurora Studio or Midnight Observatory, configure typed view settings, or import your own HTML/CSS ZIP. **Use with Local UI** reuses the adapter and preserves existing subscriptions. Save and publish to pin the view, then regenerate exported apps. See [Local UI template setup and customization](docs/operations/ui-templates.md) for complete configuration, bundles, settings schemas and the renderer API.

See [events, adapters, templates, and generated applications](docs/operations/events-and-generated-apps.md) for plugin reporting, delivery/retention guarantees, Kafka/Confluent setup, SSE replay, template customization, and both generator workflows.

## Generated application troubleshooting

Generated launchers report the plugin and underlying error, including the name of a missing secret environment variable. Add `--debug` to print environment variables and a stack trace; values for secret references and variables with sensitive names are redacted. `OLLAMA_TIMEOUT_SECONDS` overrides the timeout embedded in generated Ollama plugin settings. Ingestion validates that the input file exists and is readable before configuring providers or running external preflight. The SLF4J no-provider warning selects no-op logging and does not identify a pipeline failure.

If a workflow uses `secret://env/NEO4J_PASSWORD`, export that variable in the same shell that launches Java. In Git Bash, use `read -rsp 'Neo4j password: ' NEO4J_PASSWORD; echo`, then `export NEO4J_PASSWORD`. Running a `.bat` or `.ps1` helper as a child process does not set variables in its parent Bash shell. See the generated README for configuration instructions and use an input path on one line, for example `D:/data/document.pdf`.

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

## Connection profile updates

Connection profiles remain editable while drafts use them. Drafts keep a live profile reference, and node-specific settings override the current profile. Publishing copies the merged settings into both workflow snapshots and removes those live references. Editing a profile affects drafts and future publications; existing published versions and generated projects keep their captured settings.

Secrets remain `secret://env/NAME` references in snapshots and resolve at execution time. Changing a variable's secret value can therefore rotate credentials without republishing. Older reference-based published versions are materialized into a separate persistent snapshot on first access or before their profile is changed; their original published JSON is preserved. Deleting a profile is blocked only while a draft references it.

## Local runtime configuration

Useful environment variables include:

| Variable | Default | Purpose |
| --- | --- | --- |
| `PORT` | `8080` | HTTP listener port. |
| `BIND_ADDRESS` | `127.0.0.1` | Bind address. Keep loopback unless deployment security is configured. |
| `PLUGINTELL_DB_PATH` | `./data/plugintell.db` | SQLite control-plane database. |
| `PLUGINTELL_PLUGIN_ROOT` | `./plugins` | Directory scanned for built plugin JARs. |
| `PIPELINE_INPUT_ROOT` | `./data/ingestion` | Allowed root for local ingestion inputs and uploads. |
| `MAVEN_HOME` | Maven on `PATH` | Maven used by Studio project builds. |
| `MAVEN_REPO_LOCAL` | Maven default | Optional Maven local repository. |
| `PIPELINE_BUILD_TIMEOUT_SECONDS` | `3600` | Generated-project build timeout. |
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
- [Studio workbench](docs/operations/studio-workbench.md)
- [Plugin developer guide](docs/plugins/developer-guide.md)
- [MCP interface](docs/mcp/README.md)
- [Pipeline examples](docs/examples/README.md)
- [Python launcher developer guide](GENERATOR.md)
- [GraalVM Native Image guide](NATIVE-IMAGE.md)

