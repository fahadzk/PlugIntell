# Plugin Pipeline Architecture

This project is a small, in-process Java plugin system. The core process accepts plugin JAR paths in command-line order, checks each JAR’s manifest and implementation, validates the complete type chain, then passes each plugin’s result to the next plugin in the same JVM.

## Components

| Component | Responsibility |
| --- | --- |
| `pipeline-api` | Defines the shared `Plugin` interface and `PluginManifest` record. |
| `PluginManifestReader` | Opens a plugin JAR, finds `META-INF/PluginManifest.yaml`, parses safe YAML, and checks required fields. |
| `PluginLoader` | Creates one classloader per JAR, loads its declared entrypoint, checks the `Plugin` interface, constructs it, and compares runtime metadata with the JAR manifest. |
| `PipelineValidator` | Checks plugin count, duplicate IDs, supported API version, and adjacent input/output type names. |
| `Pipeline` | Calls each plugin in order, handing each result to the next plugin. |
| `PipelineMain` | Coordinates loading, validation, reporting, and execution. |

## Chain-of-responsibility flow

The execution is a fixed, ordered chain of handlers. Each plugin performs one step and returns the value for the next plugin. The chain order comes directly from the JAR arguments:

```text
args[0]               args[1]               args[2]              args[3]
extract-text  ──────▶  static-splitter  ───▶  test-embedder  ───▶  test-writer
File                   TextDocument           SegmentCollection       EmbeddingCollection
  └─ returns String ─────┘ returns List ─────────┘ returns vectors ─────┘ stores result
```

This is a linear Chain-of-Responsibility-style pipeline: every configured plugin receives the value from the previous plugin. There is no handler selection, branching, or skip behavior. A thrown exception stops the chain. The `Pipeline` implementation is deliberately small:

```java
Object current = initialInput;
for (Plugin plugin : plugins) {
    current = plugin.execute(current);
}
```

The manifests declare logical type names. Before the loop starts, the validator requires each plugin’s `input.type` to exactly match the prior plugin’s `output.type`. For the example:

```text
File → TextDocument → SegmentCollection → EmbeddingCollection → StoredResult
```

The core compares those names; it does not implement a general Java type system or inspect the runtime object class between steps.

## Startup and preflight sequence

`PipelineMain` treats each command-line argument as a JAR path. For every path, in order:

1. `PluginManifestReader` opens the JAR and reads the manifest entry. The JAR filename is not used as the plugin ID.
2. The reader checks the `plugin` section and required fields: ID, name, version, API version, entrypoint, input type, and output type.
3. `PluginLoader` creates a dedicated `URLClassLoader` for that JAR. Plugin implementation classes are found in that JAR first. `com.core.docintel.pipeline.api` classes delegate to the parent so core and plugins share the same `Plugin` class identity.
4. The loader loads the declared entrypoint, confirms it implements `Plugin`, calls its public no-argument constructor, and compares the implementation’s `manifest()` value with the JAR declaration.
5. After all plugins load, `PipelineValidator` checks duplicate IDs, API compatibility, and every neighboring output/input pair.

Only if all five steps succeed does `PipelineMain` construct and execute the pipeline. A bad JAR or incompatible pair exits with an error before any plugin’s `execute` method is called.

## Execution of the example

Run from the project root so the relative sample input exists:

```powershell
java -jar pipeline-core/target/pipeline-core.jar `
  plugins/extract-text/target/extract-text-plugin.jar `
  plugins/static-splitter/target/static-splitter-plugin.jar `
  plugins/test-embedder/target/test-embedder-plugin.jar `
  plugins/test-writer/target/test-writer-plugin.jar
```

The first input is `sample.txt`, passed to `extract-text` as a `java.io.File`:

1. **extract-text** reads UTF-8 and returns a `String` (`TextDocument`).
2. **static-splitter** splits the string into fixed 80-character pieces and returns a `List<String>` (`SegmentCollection`).
3. **test-embedder** derives repeatable fake vectors from each segment and returns a list of maps (`EmbeddingCollection`).
4. **test-writer** reports the count and returns an in-memory result (`StoredResult`).

The call to `execute` for each step happens synchronously in the core JVM. The example test plugins do not start a process or communicate over a network.

## Classloader boundary

Each plugin has its own `URLClassLoader`. The loader is child-first for plugin implementation classes and parent-first for Java platform classes and the shared plugin API. This prevents a plugin JAR from providing a private copy of `Plugin` that would fail `Plugin.class.isAssignableFrom(...)`. The classloader boundary is useful for class separation but is not a security sandbox.

Plugin examples exchange JDK types (`File`, `String`, `List`, and `Map`). That keeps the inter-plugin values usable even though implementation classes are loaded from separate JARs.

## Invalid-chain example

`test-writer` requires `EmbeddingCollection`. Placing it directly after `extract-text` creates this invalid connection:

```text
TextDocument → test-writer expects EmbeddingCollection
```

Run:

```powershell
java -jar pipeline-core/target/pipeline-core.jar `
  plugins/extract-text/target/extract-text-plugin.jar `
  plugins/test-writer/target/test-writer-plugin.jar
```

The core reports the type mismatch and exits nonzero. It does not print `Executing...`, read `sample.txt`, or call either plugin’s `execute` method.

## Limitations

- This is a single linear chain; there are no branches or parallel steps.
- Logical type compatibility is exact string equality only.
- The system does not resolve plugin-specific third-party dependencies.
- Classloaders do not sandbox plugin code.
- The writer is in-memory and the fake vectors are not semantic embeddings.
- The example uses a fixed relative input path and a fixed segment size.

## PDF RAG flows

The RAG demonstration uses two validated chains rather than forcing indexing and question answering into one linear chain:

```text
Ingestion: File -> TextDocument -> ChunkCollection -> EmbeddingCollection -> StoredResult
Query:     SearchRequest -> RetrievalResult -> AnswerResult
```

`TextDocument` stores extracted text page by page. `TextChunk` preserves the document ID, filename, page, and ordinal. Those records are defined in `pipeline-api`, so their class identity is shared through the parent classloader even though each implementation is loaded from its own plugin JAR.

`RagPipelineMain ingest` loads all four plugin JARs, checks manifests and logical types, then calls each optional `PreflightPlugin`. A per-validation `PreflightContext` carries discovered embedding dimensions from the Ollama plugin to the Neo4j writer. The writer and retrieval plugin verify the configured index's label, property, state, and dimensions before any `execute` call. The query mode embeds the search request, runs Neo4j's vector procedure, then asks Ollama to answer from the returned context. The core displays source/page references unless citations were disabled.

The plugin stages still run synchronously in the same JVM. Ollama and Neo4j are network services by design. Apache Tika's Tesseract OCR parser invokes the installed Tesseract executable for scanned pages; this OCR engine is a helper process, not a separate plugin stage. The OCR plugin's output and subsequent Java plugin steps remain in the core JVM.
