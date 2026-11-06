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

The server listens on `127.0.0.1:8080` by default. Open <http://127.0.0.1:8080/ui/studio.html> for Studio; `/` redirects there. The legacy generator UI and unversioned endpoints are removed. Drag nodes by their headers; connect the visible output/input controls or use the accessible dropdown. Node layout coordinates alone are saved in browser local storage; pipeline settings and secrets are not.

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
- `MAVEN_HOME`, `MAVEN_REPO_LOCAL`, and `PIPELINE_BUILD_TIMEOUT_SECONDS` configure Studio project builds.

## Studio workflows

Document ingestion appears above document query on one canvas. Use the catalog usage filter below search, then add or drop a compatible plugin into its lane. Connections stay inside that lane. Connection profiles can be reused across both lanes. Save and publish the project to version both workflows together. Either lane may remain empty; a project must contain at least one workflow. Each lane Run button selects its published workflow in Run and monitor; enter the input first if needed. Runs always execute the selected published snapshot, including when the draft has newer unsaved changes. Existing single-workflow pipelines load in the appropriate lane and are upgraded only when saved; historical published versions remain intact.

## Studio project builds

Click **Create project** to open the build tab. Choose a published RAG project with a connected linear graph in each nonempty lane, enter an absolute empty or absent folder on the backend machine, and select JAR or native executable. Paths containing symlinks or junctions are rejected. Branching, fan-in, cycles, disconnected graphs, and repeated plugin IDs within one lane are unsupported. The backend uses Maven and leaves generated files in place even after failure. Node configuration is compiled independently into each workflow; secret references resolve from their named environment variables at runtime. Generated environment scripts provide default placeholders. If a reference uses a custom variable name, set that variable yourself.

For JAR output, download the ZIP and extract it. From the extracted project directory, run `java -jar target/<lowercase-project-name>-1.0.0.jar --mode ingestion --input <input-file>` with Java 21; use `--mode query --query "Your question"` for query workflows. Keep `lib/` and `target/` together because the manifest classpath is relative. A bare JAR download is not a complete runnable distribution. Native output requires GraalVM Native Image and a compiler toolchain for the backend host; some plugins need additional reflection/reachability metadata. Executables are specific to that host platform.

The tab polls estimated progress and the last 2,000 log lines. Job records are in memory with no expiry or recovery and disappear on backend restart, including their download URLs; use the generated directory afterward. Monitor memory/disk usage and remove old generated projects manually when no longer needed. Build timeout defaults to 3,600 seconds. Raw Maven logs in failed project folders may contain tool output; keep these folders private. API logs/errors redact known secret-named environment values, but cannot guarantee removal of arbitrary third-party tool output.

Unauthenticated builds require the server to bind to loopback. With authentication enabled, ADMIN and EDITOR can start builds; ADMIN, EDITOR, OPERATOR, and VIEWER can read status and download results. Treat designers as trusted: project builds run Maven and write as the backend OS account. This is not a multi-tenant build service.

## Generated launcher diagnostics

The generated application reports a failing plugin and its underlying cause. Missing secrets name the variable to export; secret values stay out of generated source and diagnostic output. Pass `--debug` to list environment variables and show a stack trace; values referenced as secrets and values under sensitive-looking names are redacted. `OLLAMA_TIMEOUT_SECONDS` overrides the generated Ollama request timeout; export it in the same shell as Java (for example, `export OLLAMA_TIMEOUT_SECONDS=600` in Git Bash). An unreadable or missing ingestion file fails before any external preflight or ingestion. Keep input paths on one line; Windows Java accepts forward-slash paths.

For Git Bash, export required secret variables in the same terminal as Java. To enter a Neo4j password without placing its value in shell history:

```bash
read -rsp 'Neo4j password: ' NEO4J_PASSWORD; echo
export NEO4J_PASSWORD
java -jar target/myragpipeline-1.0.0.jar --mode ingestion --input 'D:/data/document.pdf'
```

Use the password for the configured Neo4j account; the Studio login password is a separate credential. A `.bat`/`.ps1` script launched from Bash cannot modify the parent Bash environment. The SLF4J no-provider warning alone is not the cause of a pipeline failure. Rebuild/re-download generated applications to receive updated launcher diagnostics.

## Provider configuration

Plugin settings are declared in each `META-INF/PluginManifest.yaml`. Create reusable profiles from the Studio's **Connection profiles** panel or `/api/v1/connections`; one profile can bundle configurations for multiple plugins, each pinned to its plugin version. Secret fields must contain a reference such as `secret://env/NEO4J_PASSWORD`; the process resolves the named environment variable at execution time. The value is never stored in a profile or pipeline snapshot or returned by the plugin API. Add settings for each plugin that should reuse the profile, then select it on matching pipeline nodes. Node-level values override the saved settings. Profiles remain editable: saved drafts use the updated settings, with node-specific values taking precedence. Publishing captures the merged settings for each workflow and removes live profile references from the published snapshot. To deploy changed settings, save and publish the draft again; older versions keep their captured settings. Existing reference-based published versions are frozen on first access or before profile changes without rewriting their original JSON. A profile can be deleted after removing its references from all current drafts; published snapshots continue to work. Secret references are captured as references, so changing the environment variable value can rotate credentials for all versions that reference that variable.

## Security and current limits

The server binds to loopback by default. Optional SQLite-backed account authentication and role checks are enabled with `CONTROL_PLANE_AUTH_ENABLED=true`; set `CONTROL_PLANE_BOOTSTRAP_ADMIN_PASSWORD` on first startup, and optionally set `CONTROL_PLANE_BOOTSTRAP_ADMIN_USERNAME` (default `admin`). Roles are ADMIN, EDITOR, OPERATOR, and VIEWER. ADMIN can create, role-change, and disable accounts; the last active administrator is protected, and disabling an account revokes its sessions. Authorized/denied REST mutation attempts and MCP query/ingestion tool invocations are recorded without request bodies and can be read by an ADMIN at `/api/v1/audit-events`. Use TLS and an authenticated proxy before serving a shared network. Tenant isolation, comprehensive read auditing, and external secret management remain unimplemented. Plugins execute in-process and are trusted Java code. The Studio runtime supports single-root DAGs, declared fan-in and parallel-safe stages, with per-execution concurrency set by `PIPELINE_EXECUTION_MAX_PARALLELISM` (default 4). Per-node retry/backoff/timeout policies are supported. Query and ingestion job state and traces persist in SQLite, and work interrupted by a restart is marked failed.
