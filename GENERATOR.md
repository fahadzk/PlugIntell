# Generate a Native Pipeline Launcher

`tools/generate_native_launcher.py` creates a Java launcher source file and a Maven `pom.xml` for either a generic fixed chain or the complete two-flow RAG application. It creates this layout:

```text
Fahad_Native_RagPipeline_Latest/
├── pom.xml
└── src/main/java/com/fahad/rag/pipeline/NativePipelineMain.java
```

## Generate the complete RAG project

The RAG workflow has two type-distinct chains in one executable: PDF ingestion writes vectors to Neo4j; query mode embeds an interactive question, retrieves matching chunks, and asks Ollama for a grounded answer. Generate one launcher containing all six plugin implementations, but validate and execute only the selected chain:

```powershell
python tools/generate_native_launcher.py `
  --ingest-plugins 'com.core.docintel.plugins.PdfOcrToTextPlugin,com.core.docintel.plugins.CitationTextSplitterPlugin,com.core.docintel.plugins.OllamaEmbeddingPlugin,com.core.docintel.plugins.Neo4jWriterPlugin' `
  --query-plugins 'com.core.docintel.plugins.Neo4jRetrievalPlugin,com.core.docintel.plugins.OllamaAnswerPlugin' `
  --output 'D:\Work\AIProjects\PlugIntell' `
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

`query` prompts for the search question. The generated launcher runs preflight and full chain validation before prompting or executing a stage. Configure Ollama, Neo4j, and Tesseract as described in [README.md](README.md). For a GraalVM executable, set `JAVA_HOME` to GraalVM 21 and run `mvn -Pnative clean package` in the generated project. Native Image closes the plugin set at build time; all six listed implementations are statically reachable through direct constructors. Tesseract remains an installed executable used by Tika for OCR on scanned PDF pages.

The ingestion and query flows cannot be concatenated into one linear chain: ingestion ends with `StoredResult`, while retrieval accepts `SearchRequest`. The generated launcher exposes them as separate modes while packaging both in one application.

## Generate a generic single-chain project

Run from the `plugin-pipeline` root. Plugin class names must be fully qualified and comma-separated:

```powershell
python tools/generate_native_launcher.py `
  --plugins 'com.core.docintel.plugins.ExtractTextPlugin,com.core.docintel.plugins.StaticSplitterPlugin,com.core.docintel.plugins.TestEmbedderPlugin,com.core.docintel.plugins.TestWriterPlugin' `
  --output 'D:\Work\AIProjects\PlugIntell' `
  --project-name 'Fahad_Native_RagPipeline' `
  --main-class 'NativePipelineMain' `
  --package 'com.fahad.rag.pipeline' `
  --dependency 'com.core.docintel:extract-text-plugin:1.0.0' `
  --dependency 'com.core.docintel:static-splitter-plugin:1.0.0' `
  --dependency 'com.core.docintel:test-embedder-plugin:1.0.0' `
  --dependency 'com.core.docintel:test-writer-plugin:1.0.0'
```

The complete RAG project command above creates:

```text
D:\Work\AIProjects\PlugIntell\Fahad_Native_RagPipeline_Latest
```

The generic project example above is written under:

```text
D:\Work\AIProjects\PlugIntell\Fahad_Native_RagPipeline
```

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

The generator adds the plugin dependencies automatically for RAG projects. From this repository root, build the shaded launcher JAR (using this repo's `.mvn/maven.config` and shared Maven cache) with:

```powershell
mvn -f ..\Fahad_Native_RagPipeline_Latest\pom.xml clean package
```

For the native executable, configure GraalVM as `JAVA_HOME`, ensure the supported C/C++ toolchain and Windows SDK are available, then run from the same repository root:

```powershell
mvn -Pnative -f ..\Fahad_Native_RagPipeline_Latest\pom.xml clean package
```

The profile uses GraalVM Native Build Tools `0.9.19`, the generated main class, and `--no-fallback`. It also adds Jakarta Mail API `2.1.3` to the native profile because Angus Activation's Native Image feature inspects Jakarta Mail handler types, even when the launcher does not use those types directly. This dependency is scoped to `-Pnative`; a regular JVM build does not need it. The executable is written to `target/<project-artifact-id>.exe` on Windows; for the RAG example above, it is `target/fahad_native_ragpipeline_latest.exe`. See [NATIVE-IMAGE.md](NATIVE-IMAGE.md) for prerequisites and toolchain setup. The generated launcher embeds the listed classes in the native executable; adding or removing a plugin means regenerating the source, updating dependencies, and rebuilding.

The plugin list argument must have commas between every class name. For example, `PluginA,PluginB`; adjacent names without a comma are treated as one invalid class name.
