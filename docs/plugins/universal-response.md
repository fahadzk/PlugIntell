# Universal Response Renderer

Direct `RetrievalResult` views identify themselves as retrieved data without a generated prose answer and surface retrieval warnings. Add an answer plugin before the renderer when a natural-language answer is required. Table cells explicitly display null, missing, empty text and empty lists rather than showing blank cells; zero and false remain visible values.

`universal-response` is a Query / Output plugin that owns its embedded JDK `HttpServer`, bundled vanilla JavaScript UI, renderer registry, response normalization and artifact storage. It uses Java 21 and Jackson, with no Micronaut/Python runtime, frontend service, CDN or extra LLM calls.

## Studio and pipeline integration

1. Build the repository and restart Studio.
2. Open a query workflow and connect **Universal Response Renderer** after the answer generator or directly after a retriever returning graph data.
3. Set the UI options, publish, and create the generated Java project.
4. Run `run.bat --mode query --query "Show monthly revenue as a chart"` (or the generated JAR). Open the URL printed by the plugin, normally http://127.0.0.1:8765/.

The repository's real types are **`AnswerResult`** and **`RetrievalResult`**, rather than `RetrieveResult`. The plugin accepts both and returns the exact original Java object, preserving answer text, citations, chunks and graph metadata for existing pipeline services. Its internal `ResponseModel` has no effect on the shared result records. See the [complete example query pipeline](../../examples/universal-response-query.json); replace model names and configure the existing retrieval database/index before running that example.

Minimal node configuration:

```json
{
  "id": "display",
  "pluginId": "universal-response",
  "pluginVersion": "1.0.0",
  "config": {
    "UR_UI_ENABLED": true,
    "UR_PORT": 8765,
    "UR_AUTO_OPEN_BROWSER": true,
    "UR_OUTPUT_MODE": "auto",
    "UR_THEME": "system"
  }
}
```

The viewer keeps the JVM running after a CLI query completes so that its URLs remain usable. **Ctrl+C stops the application and clears its temporary responses.** In an embedded host, `ApplicationScopedPlugin.closeApplication()`, `UniversalResponsePlugin.shutdownServer()` or `PluginLoader.closeApplicationPlugins()` supplies explicit application shutdown. Per-query `close()` leaves the server alive. Disabling `UR_UI_ENABLED` normalizes the result without starting a server or opening a browser. Headless systems can serve the viewer without desktop integration; an unavailable browser is a warning, not a pipeline failure.

## Configuration

Fields use the existing flat plugin configuration mechanism, with same-named environment variable fallbacks. Explicit node settings take precedence. The manifest is authoritative for Studio forms and validation.

| Setting | Default | Purpose |
| --- | --- | --- |
| `UR_OUTPUT_MODE` | `auto` | Prefer a renderer; incompatible requests produce warnings and a compatible fallback |
| `UR_UI_ENABLED` | `true` | Enable embedded server and downloads |
| `UR_HOST` | `127.0.0.1` | Only loopback binding is supported |
| `UR_PORT` | `8765` | Port; 0 selects an available port |
| `UR_AUTO_OPEN_BROWSER` | `true` | Open a viewer once per application when supported |
| `UR_OPEN_EVERY_RESPONSE` | `false` | Explicitly request a new browser view for every response |
| `UR_THEME` | `system` | `system`, `light` or `dark`; user theme choice is remembered locally |
| `UR_RENDERERS` | all built-in types | Comma-separated enabled renderers; text fallback always works |
| `UR_MAX_RESPONSES` | `100` | Retained response count, maximum 1000 |
| `UR_MAX_AGE_MINUTES` | `60` | Absolute response retention, maximum 1440 minutes |
| `UR_MAX_RESPONSE_BYTES` | `1048576` | Serialized response budget, configurable from 1 KiB to 8 MiB |
| `UR_MAX_STORE_BYTES` | `33554432` | Serialized response/artifact memory budget, 1–256 MiB |
| `UR_ARTIFACTS_ENABLED` | `true` | Create actual in-memory exports; no filesystem output directory |
| `UR_ALLOW_REMOTE_MEDIA` | `false` | Allow an explicit user click to load HTTPS media in the browser |

Applications must use consistent port/storage settings across renderer instances. Conflicting settings produce an explicit configuration error. Occupied ports fail clearly, with advice to change the port or disable the UI. The server never silently takes another application's port. Storage holds serialized payloads and generated artifacts, plus small index metadata; ordinary Java object and HTTP buffer overhead also consumes memory. Responses and their exports are evicted together by age, count and byte budget. A response too large for the budget becomes a bounded warning view; its original pipeline result is still returned unchanged.

## Response contract and detection

The viewer JSON contract includes schemaVersion `1.0`, a generated UUID, UTC creation timestamp, query, status, independent blocks, source references, metadata, warnings and artifact references. Ordinary answers are detected as Markdown, JSON/rows, code, XML, SVG, HTML or supported diagram syntax. Configuration and modest query hints such as "as a chart" express presentation preference; data compatibility is still validated. No missing chart points, graph nodes or relationships are invented.

For explicit multi-format output, the answer string can contain this JSON envelope, or a `RetrievalResult.graph` can contain it under `presentation`:

```json
{
  "blocks": [
    {"type": "markdown", "data": "## Revenue\nQuarterly results follow."},
    {"type": "table", "data": [{"month": "Jan", "revenue": 120}, {"month": "Feb", "revenue": 170}]},
    {"type": "graph", "data": {
      "nodes": [{"id": "r", "name": "Rawalpindi"}, {"id": "k", "name": "Karachi"}],
      "edges": [{"source": "r", "target": "k", "type": "NEXT_STATION"}]
    }},
    {"type": "code", "data": {"language": "java", "content": "public String destination() { return \"Karachi\"; }"}}
  ]
}
```

Mixed prose and fenced blocks are also detected independently. A failed/disabled/unknown block becomes safe text with a warning while other blocks remain interactive. Maximum: 32 blocks, 5000 table rows, 100 columns, 20 chart series, 500 graph nodes and 2000 edges. Table pages contain 100 rows; charts preview at most 200 rows while table/JSON views and exports retain the full normalized data. Code previews show up to 100000 characters, while Copy retains the full block. SVG is limited to 30 levels and 5000 elements; HTML markup is bounded separately. Oversized content falls back honestly.

Citation references contain source ID/name/page/score only, honoring the result's citations flag. Raw retrieval chunk text, evidence quotes, plans and internal diagnostics are not automatically published. Retrieval graphs are reduced to display node/edge fields, preserving supplied array order and direction. This also applies when a graph renderer is disabled. The original retrieval object remains intact for the pipeline.

## Working renderers and interactions

- Markdown: headings, paragraphs, emphasis, lists, blockquotes, inline/fenced code and pipe tables. Raw HTML remains text. Plain text always has a safe fallback.
- JSON/XML/code: readable, lightweight lexical highlighting and copy controls; code/SQL is never executed.
- Tables: stable sorting, filtering, scrolling and paging. Compatible table/JSON/chart views switch locally.
- Charts: native SVG bar, line and pie charts, series toggles, tooltips and SVG downloads. Numeric fields must be finite. Pie views require non-negative data and use the first visible series.
- Graphs: ordered nodes and directed edges, dragging, panning, zoom, inspection, fullscreen expansion, table/JSON views and SVG downloads. This is a deterministic lightweight layout, not an automatic force-directed layout engine.
- Mermaid-compatible diagrams: basic `graph`/`flowchart` directions, labeled nodes, directed links (`-->`, `==>`, `-.->`), and simple `sequenceDiagram` participant/message syntax. **Full Mermaid grammar, subgraphs, styling directives, class/state/Gantt diagrams and advanced sequence controls are not implemented.** Unsupported syntax is shown as highlighted source with an explanation; it is not claimed to have rendered.
- SVG/HTML: allowlist-sanitized and displayed inside script-disabled sandboxed frames. HTML attributes are removed; SVG external references and active elements are removed.
- Media: image/audio/video HTTPS references. No remote media is fetched by the server or automatically loaded. When explicitly enabled, a user click requests it from the browser.
- Widgets: text/number inputs, dropdowns, checkboxes, buttons, arithmetic calculators, counts, filters and tables. Actions are predefined client-side operations, with no generated JavaScript, `eval`, dynamic functions or server-side actions.

Example widget:

```json
{"type":"widget","data":{
  "title":"Cost calculator",
  "fields":[{"id":"fare","type":"number","label":"Fare","value":"120"},{"id":"extras","type":"number","label":"Extras","value":"30"}],
  "actions":[{"label":"Calculate total","action":"sum","inputs":["fare","extras"]}]
}}
```

Widget actions are `sum`, `subtract`, `multiply`, `divide`, `count`, `filter` and `reset`. `filter` uses an optional `rows` array and declared input values. Fields/actions/options have explicit count limits, and unknown actions are rejected. No generated endpoint name or script is accepted.

## Artifacts and extension points

Initial server exports produce real **CSV, JSON, static HTML and SVG** bytes. Graph SVG and sanitized SVG blocks receive SVG artifacts; graph/chart tools also support client-generated SVG downloads. CSV fields are quoted, and formula-like string cells are prefixed to reduce spreadsheet formula injection. At most 12 files and 8 MiB of artifact bytes are retained per response, within the overall store budget. Names are safe basenames; IDs are generated UUIDs. Downloads never resolve a user-supplied filesystem path.

`ArtifactManager.Exporter` is an explicit trusted-code extension point. Approved MIME types include document and binary media formats so a future exporter can supply real bytes. **PDF, DOCX, XLSX, PPTX and binary media generation are not bundled**; requesting them produces a warning and compatible content. No placeholder file is advertised. A future exporter must use real bytes, an allowlisted MIME type and a safe filename. `RendererRegistry.Handler` and the browser `renderers` map are explicitly registered, with no reflective component discovery.

## HTTP and security

| Route | Purpose |
| --- | --- |
| `GET /` | Latest response and response history |
| `GET /responses/{uuid}` | Viewer for a retained response |
| `GET /api/responses` | Response index without bodies |
| `GET /api/responses/latest` | Latest normalized response |
| `GET /api/responses/{uuid}` | One normalized response |
| `GET /api/artifacts/{uuid}` | Attachment bytes |
| `GET /health` | Status and retained count |

HEAD is supported. Other methods are rejected. The server validates Host, Origin, Fetch Metadata and request paths; query strings, encoded traversal paths, unexpected bodies and oversized headers are rejected. There is no CORS permission for foreign sites, filesystem route or remote proxy. Responses use CSP, nosniff, same-origin resource policy, no-store caching and no-referrer policy. HTML/SVG attachments have a restrictive sandbox CSP. JSON has bounded parser depth, no polymorphic deserialization/default typing, and no executable response payloads. The fixed worker pool has a bounded request queue.

Loopback limits network exposure, but other processes under the same local user can access the viewer. Remote binding is unsupported. Avoid displaying private responses on an untrusted/shared machine. Lifecycle logs report response IDs, types, counts, URLs and durations, not complete answers or retrieval context. HTTP error responses omit stack traces, content and configuration values.

## Lifecycle and necessary additive API changes

Two small shared API utilities were added without changing `Plugin.execute`, `AnswerResult` or `RetrievalResult`:

- `PluginTypes.accepts` supports explicit port unions (`AnswerResult|RetrievalResult`) consistently in runtime, Studio graph validation, project generation and the Studio connection UI. Output alternatives must all be accepted; unrelated type checks remain strict.
- `ApplicationScopedPlugin` opts in to an application-owned implementation classloader in Studio's existing loader. This is necessary because per-run classloaders otherwise create independent server singletons. Instances are still created per node/run. Only marked plugins share their implementation loader; application shutdown closes their resources and loaders. Rebuilt marked JARs require a host restart. This reuses the existing loader boundary, with no new reflective discovery or classpath scanning.

Generated applications directly link selected plugins, so their plugin singleton has natural application lifetime. Server cleanup uses an explicit close method plus a JVM shutdown hook. The application-lifetime keeper permits the viewer to survive query completion. In-memory exports disappear on shutdown or eviction. Unrelated generated applications do not include this plugin, its UI or Jackson dependency solely because the feature exists in the catalog.

## Build, run and verification

From the repository root, with JDK 21:

```powershell
mvn -pl pipeline-builder-api,plugins/universal-response -am package
plugins\universal-response\run-demo.bat --no-browser
```

Linux/macOS: `sh plugins/universal-response/run-demo.sh --no-browser`. The demo serves http://127.0.0.1:8765/; `--port 0` selects an available port. Its illustrative Rawalpindi-to-Karachi route requires no model or database and is not a timetable or current service recommendation. It includes Markdown, employee JSON, revenue data, code, graphs, diagrams, widgets, safe previews and downloads. A server with no desktop integration is still useful through its printed URL.

Automated checks:

```powershell
mvn test
node --test plugins/universal-response/src/test/js/renderers.test.mjs
plugins\universal-response\verify-generated.bat
```

`verify-generated.bat` uses the real renderer JAR and project generator, tests repeated Studio classloader executions, exports/compiles/runs an offline query JAR, verifies bundled assets and context privacy, and ensures no separate Micronaut host library was included. Its generated files/logs are under the module's ignored `target/` directory. On other platforms, compile/run `src/test/integration/GeneratedApplicationSmoke.java` against the packaged builder JAR using the platform's classpath separator.

The browser smoke test is `src/test/js/browser-smoke.mjs <CDP browser base URL> <viewer base URL>`, against a dedicated headless Chromium/Edge instance and the running demo. It checks interactions, downloads, sanitization isolation, themes, responsive layout and absence of external resource requests, and saves desktop/mobile screenshots under `target/`. JVM packaging and browser behavior are covered; GraalVM native-image output is not validated.

Verification completed in this workspace: full Maven reactor **216 tests, 212 passed, 4 skipped, zero failures/errors**; three JavaScript unit tests passed; generated-JAR/Studio-classloader smoke passed; headless Edge interactions and desktop/mobile layouts passed. Follow-up targeted Java tests and generated-JAR checks also passed after the CLI viewer-link redaction fix. Screenshots of the response layout, directed graph and sanitized SVG/HTML previews were inspected. No external model or database was required for these checks.

## File map

- `plugins/universal-response/pom.xml`, manifest and demo scripts: module/catalog/export packaging.
- `UniversalResponsePlugin`, `ResponseConfig`, `ResponseModel`, `ResponseJson`, `ResponseDetector`, `RendererRegistry`: configuration, pass-through processing, serialization and normalization.
- `SafeContent`, `ArtifactManager`, `ResponseStore`, `EmbeddedResponseServer`: sanitization, actual exports, bounded retention and application HTTP lifecycle.
- `UniversalResponseDemo`: offline, runnable demonstration in the plugin JAR.
- `response-ui/index.html`, `app.css`, `app.js`, `renderers.js`: self-contained browser UI and explicit renderer functions.
- Module `src/test/java`, `src/test/js`, `src/test/integration`: processing, storage, security, lifecycle, browser and generated-app tests.
- `pipeline-api/.../PluginTypes.java`, `ApplicationScopedPlugin.java`; core validator/loader and lifecycle tests: additive type/lifecycle support.
- Builder `GraphPipelineValidator`, `PipelineService`, `PluginCatalog`, tests, and `pipeline-builder-ui/studio.js`: catalog category and typed connections. Generated `CliSupport` formats a fixed loopback viewer link from typed numeric event fields, so ordinary secret redaction cannot corrupt its public host/UUID digits. Untrusted message strings remain redacted.
- Root POM, README, this guide and `examples/universal-response-query.json`: reactor registration and setup.
