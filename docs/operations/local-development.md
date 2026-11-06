# Local development

## Requirements

- Java 21
- Maven 3.9+
- Tesseract for OCR-enabled PDFs
- Ollama with the selected embedding/chat models for live RAG execution
- Neo4j with a vector index matching `NEO4J_VECTOR_DIMENSIONS`

## Build and run

On Windows, from the repository folder run `build.bat` and then `run.bat`. The Studio frontend is packaged into the Java JAR, so the second command starts the backend, waits for its HTTP endpoint, and opens the browser. Close the backend console to stop the app. Set `JAVA_HOME`, `MAVEN_HOME`, and optionally `MAVEN_REPO_LOCAL` before launching if the tools are not on `PATH`.

From the `plugin-pipeline` directory:

```powershell
mvn test
mvn package -DskipTests
java -jar pipeline-builder-api/target/pipeline-builder-api.jar
```

The server listens on `127.0.0.1:8080` by default. Open <http://127.0.0.1:8080/> for the legacy project generator or <http://127.0.0.1:8080/ui/studio.html> for the graph Studio. Drag nodes by their headers; connect the visible output/input controls or use the accessible dropdown. Node layout coordinates alone are saved in browser local storage; pipeline settings and secrets are not.

To provide a provider secret to the backend, set its environment variable in the shell that launches `run.bat` or `run-backend.bat`. For example, in Command Prompt, replace the placeholder with your local value and then start the app:

```bat
set "NEO4J_PASSWORD=<your-local-password>"
call run.bat
```

The pipeline/profile field should contain the reference `secret://env/NEO4J_PASSWORD`, not the password itself. The repository's startup scripts intentionally do not contain provider passwords.

## Data and inputs

- `PLUGINTELL_DB_PATH` selects the SQLite metadata database (default `./data/plugintell.db`).
- `PIPELINE_INPUT_ROOT` selects the only directory accepted by REST/MCP ingestion (default `./data/ingestion`). Create it and place source files there before starting ingestion.
- `PLUGINTELL_PLUGIN_ROOT` selects the plugin source directory when it is not the repository's `plugins/` folder.
- `MAVEN_HOME`, `MAVEN_REPO_LOCAL`, and `PIPELINE_BUILD_TIMEOUT_SECONDS` configure the legacy generated-project builder.

## Provider configuration

Plugin settings are declared in each `META-INF/PluginManifest.yaml`. Create reusable profiles from the Studio's **Connection profiles** panel or `/api/v1/connections`; one profile can bundle configurations for multiple plugins, each pinned to its plugin version. Secret fields must contain a reference such as `secret://env/NEO4J_PASSWORD`; the process resolves the named environment variable at execution time. The value is never stored in a profile or pipeline snapshot or returned by the plugin API. Add settings for each plugin that should reuse the profile, then select it on matching pipeline nodes. Node-level values override the saved settings. Referenced profiles cannot be edited or deleted; create a new profile to rotate settings, update the pipeline reference, and publish a new version.

## Security and current limits

The server binds to loopback by default. Optional SQLite-backed account authentication and role checks are enabled with `CONTROL_PLANE_AUTH_ENABLED=true`; set `CONTROL_PLANE_BOOTSTRAP_ADMIN_PASSWORD` on first startup, and optionally set `CONTROL_PLANE_BOOTSTRAP_ADMIN_USERNAME` (default `admin`). Roles are ADMIN, EDITOR, OPERATOR, and VIEWER. ADMIN can create, role-change, and disable accounts; the last active administrator is protected, and disabling an account revokes its sessions. Authorized/denied REST mutation attempts and MCP query/ingestion tool invocations are recorded without request bodies and can be read by an ADMIN at `/api/v1/audit-events`. Use TLS and an authenticated proxy before serving a shared network. Tenant isolation, comprehensive read auditing, and external secret management remain unimplemented. Plugins execute in-process and are trusted Java code. The Studio runtime supports single-root DAGs, declared fan-in and parallel-safe stages, with per-execution concurrency set by `PIPELINE_EXECUTION_MAX_PARALLELISM` (default 4). Per-node retry/backoff/timeout policies are supported. Query and ingestion job state and traces persist in SQLite, and work interrupted by a restart is marked failed.
