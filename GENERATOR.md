# Generate a Native Pipeline Launcher

`tools/generate_native_launcher.py` creates an explicit Java composition root and Maven project for either a generic fixed chain or the complete two-flow RAG application. Optional capabilities select monitoring assets and the web host. A minimal project has this layout:

Provider plugins and the web host must come from the same reactor build. Their shaded Netty dependencies use the root `netty.version` BOM; generated Python web POMs import that same BOM for ordinary transitive dependencies. After upgrading that version, rebuild/install the complete reactor before building generated projects. Maven cannot mediate older Netty classes already embedded in plugin JARs. See [Native Image troubleshooting](NATIVE-IMAGE.md#troubleshooting).

```text
Fahad_Native_RagPipeline_Latest/
├── pom.xml
└── src/main/java/com/fahad/rag/pipeline/
    ├── NativePipelineMain.java
    └── PipelineAssembly.java
```

## Generate the complete RAG project

The RAG workflow has two type-distinct chains in one executable: PDF ingestion writes vectors to Neo4j; query mode embeds an interactive question, retrieves matching chunks, and asks Ollama for a grounded answer. Generate one launcher containing all six plugin implementations, but validate and execute only the selected chain:

```powershell
python tools/generate_native_launcher.py `
  --ingest-plugins 'com.core.docintel.plugins.PdfOcrToTextPlugin,com.core.docintel.plugins.CitationTextSplitterPlugin,com.core.docintel.plugins.OllamaEmbeddingPlugin,com.core.docintel.plugins.Neo4jWriterPlugin' `
  --query-plugins 'com.core.docintel.plugins.Neo4jRetrievalPlugin,com.core.docintel.plugins.OllamaAnswerPlugin' `
  --output "$env:TEMP\PlugIntellGenerated" `
  --project-name 'Fahad_Native_RagPipeline_Latest' `
  --main-class 'NativePipelineMain' `
  --package 'com.fahad.rag.pipeline'
```

The RAG mode automatically adds Maven dependencies for all six plugins in the generated `pom.xml`, plus the shared API and core. Plugin dependency artifact IDs now use the `-plugin` postfix (for example, `pdf-ocr-to-text-plugin` and `neo4j-writer-plugin`); the API, core, and support library artifact IDs do not. Run `mvn clean install` in this repository first so those artifacts are available in the local Maven repository. The generated application supports:

```powershell
java -jar target\fahad_native_ragpipeline_latest-1.0.0.jar ingest 'D:\data\manual.pdf'
java -jar target\fahad_native_ragpipeline_latest-1.0.0.jar query
java -jar target\fahad_native_ragpipeline_latest-1.0.0.jar query --no-citations --top-k=10
```

The generated project includes `rag-env.ps1` and `rag-env.bat` with all RAG environment settings. Edit the placeholders first. In PowerShell, dot-source the script so settings apply to the current shell, then run the executable:

```powershell
. .\rag-env.ps1
.\target\fahad_native_ragpipeline_latest.exe query
```

In Command Prompt, use `call rag-env.bat`, then run `target\fahad_native_ragpipeline_latest.exe query` in the same window. These files set Ollama URL/models/timeout, Neo4j URL/user/password/database/index/dimensions, and Tesseract path/language. Select `NEO4J_VECTOR_DIMENSIONS` to match the embedding model and existing Neo4j vector index.

`query` prompts for the search question. The generated launcher validates the selected chain and runs preflight before executing a stage. Query input is collected before provider preflight. Configure Ollama, Neo4j, and Tesseract as described in [README.md](README.md). For a GraalVM executable, set `JAVA_HOME` to GraalVM 21 and run `mvn -Pnative clean package` in the generated project. Native Image closes the plugin set at build time; all six listed implementations are statically reachable through direct constructors. Tesseract remains an installed executable used by Tika for OCR on scanned PDF pages.

The ingestion and query flows cannot be concatenated into one linear chain: ingestion ends with `StoredResult`, while retrieval accepts `SearchRequest`. The generated launcher exposes them as separate modes while packaging both in one application.

## Generate a generic single-chain project

Run from the `plugin-pipeline` root. Plugin class names must be fully qualified and comma-separated:

```powershell
python tools/generate_native_launcher.py `
  --plugins 'com.core.docintel.plugins.ExtractTextPlugin,com.core.docintel.plugins.StaticSplitterPlugin,com.core.docintel.plugins.TestEmbedderPlugin,com.core.docintel.plugins.TestWriterPlugin' `
  --output "$env:TEMP\PlugIntellGenerated" `
  --project-name 'Fahad_Native_RagPipeline' `
  --main-class 'NativePipelineMain' `
  --package 'com.fahad.rag.pipeline' `
  --dependency 'com.core.docintel:extract-text-plugin:1.0.0' `
  --dependency 'com.core.docintel:static-splitter-plugin:1.0.0' `
  --dependency 'com.core.docintel:test-embedder-plugin:1.0.0' `
  --dependency 'com.core.docintel:test-writer-plugin:1.0.0'
```

The complete RAG project command above creates:

`$env:TEMP\PlugIntellGenerated\Fahad_Native_RagPipeline_Latest`

The generic project example above is written under:

`$env:TEMP\PlugIntellGenerated\Fahad_Native_RagPipeline`

`--output` is the parent directory. The generator adds `--project-name` beneath it. It refuses to overwrite a project if the generated main source or `pom.xml` already exists.

## Configure plugin dependencies

The generated Java source directly constructs each plugin, so those classes must be on the Maven compile classpath and included in the executable. Supply one `--dependency groupId:artifactId:version` per plugin. For this reactor, plugin modules use these coordinates:

```xml
<dependency>
  <groupId>com.core.docintel</groupId>
  <artifactId>extract-text-plugin</artifactId>
  <version>1.0.0</version>
</dependency>
```

If the generated project is built separately from this reactor, install or publish the API, core, and plugin artifacts to a Maven repository first. The generator cannot infer Maven artifact coordinates from Java class names.

## Build and run

The generator adds the plugin dependencies automatically for RAG projects. Build the shaded launcher JAR by pointing Maven at the generated POM:

```powershell
$project = Join-Path $env:TEMP 'PlugIntellGenerated\Fahad_Native_RagPipeline_Latest'
mvn -f (Join-Path $project 'pom.xml') clean package
```

For the native executable, configure GraalVM as `JAVA_HOME`, ensure the supported C/C++ toolchain and Windows SDK are available, then run from the same repository root:

```powershell
mvn -Pnative -f (Join-Path $project 'pom.xml') clean package
```

The profile uses GraalVM Native Build Tools `0.10.6`, the generated main class, and `--no-fallback`. It also adds Jakarta Mail API `2.1.3` to the native profile because Angus Activation's Native Image feature inspects Jakarta Mail handler types, even when the launcher does not use those types directly. This dependency is scoped to `-Pnative`; a regular JVM build does not need it. The executable is written to `target/<project-artifact-id>.exe` on Windows; for the RAG example above, it is `target/fahad_native_ragpipeline_latest.exe`. See [NATIVE-IMAGE.md](NATIVE-IMAGE.md) for prerequisites and toolchain setup. The generated launcher embeds the listed classes in the native executable; adding or removing a plugin means regenerating the source, updating dependencies, and rebuilding.

The plugin list argument must have commas between every class name. For example, `PluginA,PluginB`; adjacent names without a comma are treated as one invalid class name.

## Event adapters, web hosting, and templates

Both CLI and RAG generation now write an explicit PipelineAssembly.java, a pipeline-descriptor.json resource, run scripts, and README. Built-in plugins use reporting constructors with immutable attempt reporters; other validated plugin classes keep direct no-argument construction and wrapper lifecycle events.

The default remains CLI-only and contains no web dependency or serve command. Add --web --ui --template water for the Micronaut web host and live monitor, or --kafka-bootstrap-servers localhost:9092 --kafka-topic plugintell.events for telemetry. These selections may be combined. The Kafka module must first be built/installed with Maven -Pkafka. Generated provider and adapter assembly never uses runtime reflection or ServiceLoader discovery.

`--web` selects Micronaut, the currently implemented standalone web host. `--ui` selects the Local UI event adapter; `--template water` selects its visual renderer. These are separate choices, not three host implementations. Web-enabled output retains its CLI commands. Successful query runs show their answer and source/page citations in the generated page, including when reopened from retained history. AnswerResult and String outputs use a separate generated-host result endpoint; summaries and SSE remain content-free. Results are memory-only and disappear on restart or run eviction; answers are capped at 262,144 characters and citations at 100 (with a truncation notice). Studio already has its own Micronaut host, while generated CLI-only applications cannot display the embedded monitor.

Generated startup scripts print capability-specific help without arguments. Web projects list `run.bat serve` / `sh run.sh serve` alongside ingestion/query commands. Their README explains the printed local URL, input root and console/file logs. The Micronaut host writes structured execution events to its console and `logs/server-0.log` (three rolling files, 5 MB each); `PIPELINE_LOG_DIR` changes the directory. In PowerShell use `Get-Content .\logs\server-0.log -Wait -Tail 100`. Direct plugin stdout/stderr also appears in the server console. Regenerate existing downloads to receive these updated scripts and libraries.

Web output supports java -jar target/<artifact>-1.0.0.jar serve, binds to loopback, prints its URL, and keeps serving ingestion/query controls plus snapshots/SSE/history across runs. Browser launch is optional in host settings. Web input paths must stay under PIPELINE_INPUT_ROOT (default data/ingestion). No Studio control-plane dependencies are included. Existing CLI ingest/query options remain available.

Use `--web --ui --template aurora` or `--web --ui --template midnight` for the richer bundled external views. `--template-bundle my-monitor.zip` accepts your own declarative HTML/CSS bundle; a manifest `settingsSchema` supplies typed controls in Studio and validation in both generators. Friendly bundled aliases resolve to content pins. Read [Local UI templates](docs/operations/ui-templates.md) for import, settings, archive layout and the version-1 bridge API.

Use --capabilities settings.json for explicit adapter instances, node/workflow subscriptions, filters, host and template settings. Utility node IDs are node-1, node-2, etc. per lane. Use --template-bundle template.zip for a validated version-1 custom bundle; executable renderers also require --trusted-template. Standard/water and custom templates use the shared monitor protocol and safe text rendering.

CLI and Micronaut web/UI projects include a native profile using Native Build Tools 0.10.6. Build with `mvn -Pnative package`, then use `run-native.bat serve` / `sh run-native.sh serve` for web hosting, or pass the existing CLI commands to those scripts. `run.bat` / `run.sh` retain their JVM behavior in Python-generated projects. Kafka builds omit the native profile. All selected processing plugins still require native-compatible dependencies and reachability metadata. Event delivery is bounded and best effort; monitoring SSE does not add distributed execution or provider token streaming. Standalone history is lost on restart.

See [events and generated applications](docs/operations/events-and-generated-apps.md) for settings, Kafka/Confluent secret references, templates, protocol, retention, and verification. Python generator checks: python -m unittest discover -s tools -p 'test_*.py' -v.
