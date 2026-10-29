# Minimal Java Dynamic Plugin Pipeline

A small Java 21 proof of concept: plugin JARs are discovered from command-line paths, checked against a YAML contract, loaded in the core JVM, connected by logical type names, then executed sequentially.

For a detailed walkthrough of the load, validation, and one-by-one execution flow, see [ARCHITECTURE.md](ARCHITECTURE.md).

To build the bundled four-plugin GraalVM Native Image executable, see [NATIVE-IMAGE.md](NATIVE-IMAGE.md). The executable embeds the example plugins at build time; use the regular JVM mode when plugins must be supplied dynamically as JARs at launch.

To generate a launcher project for a chosen fixed list of plugin classes, see [GENERATOR.md](GENERATOR.md).

## Architecture

`pipeline-api` contains `Plugin`, manifest and typed document/chunk/result records, plus optional preflight and lifecycle hooks. `pipeline-core` reads manifests, loads entrypoints, validates the full chain, checks external service prerequisites, and runs it. Each plugin is a separate Maven module and produces its own JAR.

The manifest is the machine-readable contract. The core checks required fields and supported API version, loads the named class, verifies that it implements `Plugin`, invokes its public no-argument constructor, and compares every API manifest field with the JAR declaration. It completes all of those checks and validates every adjacent type pair before calling the first `execute` method.

## Plugin format

Every plugin JAR contains `META-INF/PluginManifest.yaml`. For example:

```yaml
plugin:
  id: extract-text
  name: Text Extractor
  version: 1.0.0
  apiVersion: "1.0"
  entrypoint: com.core.docintel.plugins.ExtractTextPlugin
  input:
    type: File
  output:
    type: TextDocument
  capabilities:
    - text-extraction
  description: Extracts text from a UTF-8 text file.
  selection:
    useWhen:
      - Text extraction is required.
    avoidWhen:
      - OCR is required.
```

The core uses the manifest ID and entrypoint, never the JAR filename. Maven artifact IDs and produced plugin JAR filenames end in `-plugin`; manifest IDs remain stable logical identifiers. The initial parser validates the required contract fields and ignores additional descriptive metadata such as capabilities and selection hints.

Plugins implement `com.core.docintel.pipeline.api.Plugin` and return a `PluginManifest` matching their YAML. API dependency is `provided` when compiling plugin modules: the core's shaded JAR supplies the API at runtime. Plugin JARs therefore do not embed another copy of the API.

## Loading and classloaders

The core creates one `URLClassLoader` per plugin JAR. The loader finds plugin implementation classes in that JAR first, while delegating `com.core.docintel.pipeline.api` to the parent/core loader. Java platform classes are also parent-loaded. This keeps `Plugin.class` identity shared across the boundary and gives each plugin its own implementation class namespace. All plugin code still runs inside the same JVM; this is not a security sandbox.

The original example plugins exchange JDK values. The RAG plugins use small API records (`TextDocument`, `TextPage`, `TextChunk`, `EmbeddingCollection`, and retrieval result types) so provenance survives plugin boundaries. Logical pipeline types remain simple, exact string comparisons; they do not inspect runtime Java value types. Plugins that need network services implement `PreflightPlugin`; the core calls those checks only after the full chain passes its static type/API/ID checks. `ManagedPlugin` gives clients such as the Neo4j driver a cleanup hook.

## Validation and execution

The core rejects missing or malformed manifests, missing entrypoints, classes that do not implement `Plugin`, unusable constructors, runtime/manifest disagreement, unsupported API versions, duplicate IDs, and incompatible neighboring types. For RAG runs, it then checks Tesseract, Ollama model availability, Neo4j connectivity, and vector-index state. No plugin executes until all static validation and required service preflight checks pass.

The first plugin receives `Path.of("sample.txt").toFile()`, relative to the process working directory. The supplied sample file is in this project root. The extractor reads UTF-8 text, the splitter makes fixed 80-character segments, the embedder generates deterministic fake vectors, and the writer reports their count in memory.

## Build and run

Use a JDK 21 installation and Maven 3.9 or newer:

```bash
mvn clean package
```

This builds `pipeline-core/target/pipeline-core.jar` and the example plugin JARs at their respective module `target` directories. Run from the `plugin-pipeline` directory so `sample.txt` resolves:

```bash
java -jar pipeline-core/target/pipeline-core.jar \
  plugins/extract-text/target/extract-text-plugin.jar \
  plugins/static-splitter/target/static-splitter-plugin.jar \
  plugins/test-embedder/target/test-embedder-plugin.jar \
  plugins/test-writer/target/test-writer-plugin.jar
```

The Tika/LangChain4j document parser and splitter is an alternative first stage (`File -> SegmentCollection`), replacing both `extract-text` and `static-splitter`:

```bash
java -jar pipeline-core/target/pipeline-core.jar \
  plugins/document-splitter-lang4j/target/document-splitter-lang4j-plugin.jar \
  plugins/test-embedder/target/test-embedder-plugin.jar \
  plugins/test-writer/target/test-writer-plugin.jar
```

Its default segment size is 1000 characters with 100 characters of overlap. Override them with JVM properties such as `-Dplugin.document-splitter.segment-size=800` and `-Dplugin.document-splitter.segment-overlap=80` before `-jar`. Tika parser dependencies are shaded into this plugin JAR, with service descriptors merged, while the shared pipeline API remains parent-loaded.

Expected output (embedding count follows the sample text and segment size):

```text
Loading plugins...

[1] extract-text
    File -> TextDocument
    VALID
...
Validating pipeline...

extract-text         -> static-splitter       OK
static-splitter      -> test-embedder        OK
test-embedder       -> test-writer           OK

Pipeline is valid.

Executing...
extract-text      OK
static-splitter    OK
test-embedder     OK
Stored 2 embeddings.
test-writer       OK

Pipeline completed successfully.
```

To see preflight rejection, supply any plugin whose manifest declares an incompatible input between the extractor and writer, for example a plugin with `input.type: Image`. The core reports its expected input and the previous output, exits nonzero, and runs none of the chain. Plugin filenames may be arbitrary; the core follows each manifest entrypoint and ID.

## PDF ingestion and semantic retrieval

The RAG example has two distinct chains:

```text
Ingest: PDF -> PDF OCR/text -> citation-preserving chunks -> Ollama vectors -> Neo4j
Query:  command-line question -> Ollama query vector + Neo4j retrieval -> Ollama answer
```

`TextPage` and `TextChunk` keep the PDF filename and page number attached to every chunk. The `--no-citations` option suppresses citation output in the answer while retaining provenance in the structured retrieval result.

### Prerequisites

- Java 21 and Maven.
- Tesseract OCR installed on the machine running the Java pipeline. Apache Tika invokes its `tesseract` executable to OCR rendered PDF pages; the plugin stages themselves still execute in the core JVM. Set `PDF_OCR_TESSERACT_PATH` to the Tesseract installation folder if it is not on `PATH`. Optionally set `PDF_OCR_TESSDATA_PATH` and `PDF_OCR_LANGUAGE` (default `eng`). OCR uses 300 DPI by default; override with `-Dplugin.pdf-ocr.dpi=...`.
- A reachable Ollama server with the embedding and chat models already installed. The application checks `/api/tags` and does not pull models automatically.
- A reachable Neo4j 5.15+ database with a vector index named by `NEO4J_VECTOR_INDEX` (default `document_embedding_idx`) in `ONLINE` state. Preflight checks that the index label, property, and dimensions match the configured pipeline before processing begins. The Neo4j 5 vector procedure is used for query compatibility.

Configure these environment variables before running either flow:

| Variable | Default / required | Purpose |
| --- | --- | --- |
| `OLLAMA_BASE_URL` | `http://localhost:11434` | Ollama server URL |
| `OLLAMA_EMBEDDING_MODEL` | Required | Installed embedding model, e.g. `nomic-embed-text` |
| `OLLAMA_CHAT_MODEL` | Required | Installed chat model |
| `OLLAMA_TIMEOUT_SECONDS` | `60` | HTTP/model operation timeout |
| `NEO4J_URI` | `bolt://localhost:7687` | Neo4j Bolt URL |
| `NEO4J_USERNAME` | `neo4j` | Neo4j username |
| `NEO4J_PASSWORD` | Required | Neo4j password; do not commit it |
| `NEO4J_DATABASE` | `neo4j` | Database name |
| `NEO4J_VECTOR_INDEX` | `document_embedding_idx` | Existing vector index name |
| `NEO4J_VECTOR_DIMENSIONS` | `768` | Embedding dimension; must match the selected model and index |

For example, in PowerShell:

```powershell
$env:OLLAMA_BASE_URL = 'http://192.168.1.50:11434'
$env:OLLAMA_EMBEDDING_MODEL = 'nomic-embed-text'
$env:OLLAMA_CHAT_MODEL = 'llama3.1'
$env:NEO4J_URI = 'bolt://192.168.1.60:7687'
$env:NEO4J_USERNAME = 'neo4j'
$env:NEO4J_PASSWORD = '<your-password>'
$env:NEO4J_VECTOR_DIMENSIONS = '768'
```

Install the selected models on the Ollama host in advance, for example `ollama pull nomic-embed-text` and `ollama pull llama3.1`. Choose an embedding model whose output dimension matches `NEO4J_VECTOR_DIMENSIONS`.

Create the vector index once in Neo4j Browser or cypher-shell. This example is for Neo4j 5.15+; substitute the embedding dimension if your model differs:

```cypher
CREATE VECTOR INDEX document_embedding_idx IF NOT EXISTS
FOR (c:TextChunk) ON (c.embedding)
OPTIONS {
  indexConfig: {
    `vector.dimensions`: 768,
    `vector.similarity_function`: 'cosine'
  }
};
```

Build all modules and run the tests:

```bash
mvn clean install
```

Ingest a PDF (run from the `plugin-pipeline` directory):

```bash
java -cp pipeline-core/target/pipeline-core.jar com.core.docintel.pipeline.core.RagPipelineMain ingest \
  sample.pdf \
  plugins/pdf-ocr-to-text/target/pdf-ocr-to-text-plugin.jar \
  plugins/citation-text-splitter/target/citation-text-splitter-plugin.jar \
  plugins/ollama-embedding/target/ollama-embedding-plugin.jar \
  plugins/neo4j-writer/target/neo4j-writer-plugin.jar
```

The CLI validates the whole chain and checks Tesseract, Ollama, Neo4j, and index availability before extracting the first page. The embedding plugin probes the configured model and shares its vector dimension with later plugins during preflight; the writer rejects a mismatch with the configured Neo4j vector index before ingestion starts. The writer uses `MERGE` on a stable chunk ID, so ingesting the same PDF again updates its chunks instead of creating duplicates.

Start interactive retrieval with citations:

```bash
java -cp pipeline-core/target/pipeline-core.jar com.core.docintel.pipeline.core.RagPipelineMain query \
  plugins/neo4j-retrieval/target/neo4j-retrieval-plugin.jar \
  plugins/ollama-answer/target/ollama-answer-plugin.jar
```

Suppress citation markers in the answer with `query --no-citations ...`; change result count with `--top-k=10`. The CLI asks for a question after preflight succeeds, retrieves matching chunks, and passes them to the chat model. It prints the answer and (when enabled) source filenames, page numbers, and retrieval scores.

Tests use generated local PDF fixtures, fake plugin services, and a local HTTP stub for the Ollama tags endpoint. They do not require a live Tesseract, Ollama, or Neo4j service. A real OCR/Ollama/Neo4j end-to-end run requires the prerequisites above.

Implementation references: [LangChain4j Ollama embeddings](https://docs.langchain4j.dev/integrations/embedding-models/ollama/), [LangChain4j Ollama chat](https://docs.langchain4j.dev/integrations/language-models/ollama/), [Neo4j Java Driver connection checks](https://neo4j.com/docs/java-manual/current/connect/), [Neo4j 5 vector indexes](https://neo4j.com/docs/cypher-manual/5/indexes/semantic-indexes/vector-indexes/), and [Apache Tika OCR setup](https://cwiki.apache.org/confluence/display/TIKA/TikaOCR).

## Creating a plugin

Add a Maven module that depends on `pipeline-api` with `provided` scope. Implement a public class with a public no-argument constructor and the two `Plugin` methods (or specialize the API with `DocumentSplitterPlugin`). Package its matching `META-INF/PluginManifest.yaml` as a resource. Set the manifest input/output names to the values the next or previous component declares, then build its JAR and pass that path to the core. The core does not resolve additional plugin dependencies; a plugin that needs libraries must package them or otherwise make them available to its loader.

## Tests

`pipeline-core` tests create small fixture JARs and cover valid loading, missing manifest, malformed YAML, absent and invalid entrypoints, constructor failure, runtime metadata mismatch, compatible and incompatible chains, and duplicate IDs. Run with `mvn test` or as part of `mvn clean package`.

## Known limitations

- API version support is an exact `1.0` check; there is no compatibility range.
- Logical types are names only, and runtime object classes are not checked against them.
- The simple `PipelineMain` demo still uses a fixed relative sample-file path; the RAG CLI has dedicated `ingest` and `query` modes.
- Segment size is fixed at 80 characters.
- Fake vectors are for repeatable demonstrations, not semantic search.
- The writer stores results only in memory.
- Plugins shade their third-party dependencies into their JARs; the loader does not resolve Maven dependencies dynamically or provide a security sandbox.
- PDF OCR depends on an installed Tesseract executable and currently OCRs pages only when PDFBox extracts no text from that page.
- The RAG modules require service configuration and a vector index compatible with the selected Neo4j server; the tests do not contact those services.
- There is no hot reload, streaming, or workflow DAG.
