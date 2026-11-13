# Neo4j Text-to-Cypher Retrieval

`neo4j-text2cypher-retrieval` (display name **Neo4j Text-to-Cypher Retrieval**) reads existing, externally ingested Neo4j graphs. It accepts `SearchRequest` and returns the existing `RetrievalResult`. It does not require `KgEntity`, `KgFact`, full-text/vector indexes, an ingestion lane, or APOC. It never provisions database schema or writes graph data.

## Workflow

```text
Question -> schema metadata -> model-generated Cypher + parameters
         -> local validation -> Neo4j EXPLAIN read classification
         -> bounded read transaction -> RetrievalResult
         -> answer plugin (optional) -> Universal Response Renderer (optional)
```

The model generates the query, not the answer. Connect the existing Ollama Answer plugin after retrieval for prose grounded in returned rows. That answer plugin has its own provider/model configuration; selecting OpenAI or Gemini for Cypher generation does not change the answer node. Connect directly to Universal Response Renderer to inspect rows and returned graph entities instead.

This follows the same Text-to-Cypher approach as [LangChain4j's Neo4j retriever](https://github.com/langchain4j/langchain4j/blob/main/docs/docs/integrations/embedding-stores/neo4j.md), but reuses PlugIntell's existing structured Ollama/OpenAI/Gemini clients and Neo4j driver. It does not add or instantiate the community `Neo4jText2CypherRetriever`, and it is not a general loader for arbitrary `ChatModel` implementations. The shared provider client retains its existing Java class name and package, `com.core.docintel.llm.ExtractionModelClient`, and now lives in `neo4j-plugin-support` for reuse without depending on an extraction plugin.

## Configure in Studio

Add the plugin to the query lane. Use the actual database, such as `NEO4J_DATABASE=pakistan-railways`, and an account permitted to read only that database. Ingestion can remain external.

Choose `LLM_PROVIDER`:

| Provider | Required fields | Endpoint |
| --- | --- | --- |
| `OLLAMA` (default) | `OLLAMA_CHAT_MODEL` | `OLLAMA_BASE_URL`, default `http://localhost:11434` |
| `OPENAI` | `OPENAI_MODEL`, `OPENAI_API_KEY` | `OPENAI_BASE_URL`, default `https://api.openai.com/v1/` |
| `GEMINI` | `GEMINI_MODEL`, `GEMINI_API_KEY` | `GEMINI_BASE_URL`, default `https://generativelanguage.googleapis.com` |

All providers use configurable model IDs, with no recommended model silently substituted. OpenAI-compatible endpoints must implement model metadata lookup and strict JSON-schema Chat Completions; a similar envelope alone is insufficient. Namespaced OpenAI model IDs are supported, with the metadata path component URL-encoded. Cloud origins require HTTPS; loopback HTTP is available for tests. See [provider setup](knowledge-graph.md#choose-the-extraction-provider) for the shared adapter's capabilities and limits.

Store credentials as `secret://env/NEO4J_PASSWORD`, `secret://env/OPENAI_API_KEY` or `secret://env/GEMINI_API_KEY`. Resolved values are never persisted or included in results. Clear unused provider-secret references in Studio: its host resolves saved references before calling plugins. Generated CLI projects remove inactive provider fields before resolving secrets.

Interactive generated applications prompt for provider, model and base URL, with saved settings as defaults. Selected values last for the current process only. Missing required database settings and provider secrets use the existing secure-console/redaction path. `--non-interactive` skips questions and requires configured/environment values.

```text
Chat provider [OLLAMA/OPENAI/GEMINI] (Enter for OLLAMA): OLLAMA
Chat model: your-installed-chat-model
OLLAMA_BASE_URL (Enter for http://localhost:11434): https://your-ollama-service.example
```

## Schema and domain meaning

By default, the plugin reads the [built-in schema procedures](https://neo4j.com/docs/operations-manual/current/procedures/built-in-procedures/) `db.schema.nodeTypeProperties()` and `db.schema.relTypeProperties()`, then samples directed relationship patterns. Property procedures may inspect substantial data: the transaction timeout bounds runtime, not total database work. The account needs permission for these built-in read procedures. Pattern samples are explicitly marked as samples, not a complete inventory. Schema metadata exceeding `T2C_SCHEMA_LIMIT` or 32000 characters fails with guidance instead of silently truncating.

Optional `T2C_SCHEMA_JSON` supplies a trusted JSON object and skips introspection entirely. For example:

```json
{
  "nodes": [
    {"labels": ["Train"], "properties": {"name": "STRING", "description": "STRING"}},
    {"labels": ["Station"], "properties": {"name": "STRING"}}
  ],
  "relationships": [
    {"type": "STOPS_AT", "source": "Train", "target": "Station", "properties": {"sequence": "INTEGER"}}
  ]
}
```

This example is illustrative; use your actual graph properties. `T2C_INSTRUCTIONS` supplies trusted domain meanings, such as whether `STOPS_AT.sequence` records this train's travel order or a `NEXT_STOP` edge has a train identifier. The plugin does not invent route order, assume alphabetic order is travel order, or add railway-specific query templates. Metadata cannot establish business meaning or guarantee the model's interpretation. Question values are returned separately as parameters. The model is instructed to return enough fields for every question component, including descriptions for family suitability, if present.

## Limits and diagnostics

| Setting | Default | Meaning |
| --- | --- | --- |
| `T2C_SCHEMA_LIMIT` | 256 | Maximum metadata rows per category and sampled edge budget |
| `T2C_SCHEMA_TTL_SECONDS` | 300 | Schema cache lifetime within a plugin instance; zero disables caching |
| `T2C_MAX_ROWS` | 100 | Maximum returned rows; independent of `SearchRequest.topK` so a 31-stop route is not reduced to five rows |
| `T2C_MAX_RESULT_BYTES` | 262144 | Conservative accepted-result budget, including presentation overhead |
| `KG_QUERY_TIMEOUT_SECONDS` | 20 | Per-read-transaction deadline, at most 120 seconds |
| `KG_CONTEXT_TOKENS` | 16384 | Conservative UTF-8 prompt/schema budget; input is not silently truncated |
| `KG_OUTPUT_TOKENS` | 2048 | Model generation budget |
| `KG_RESPONSE_BYTES` | 262144 | Provider HTTP response limit |
| `KG_REPAIR_RETRIES` | 1 | Extra model requests after invalid output or a Neo4j statement error; may incur provider charges |
| `T2C_LOG_CYPHER` | false | Opt-in query logging; literals may contain user values |

Results include ordered `rows`, normalized `nodes`/`edges` when returned, renderer presentation blocks, provider/model/database, a query hash, duration, warnings and a truncation flag. Selected-property results render as tables; returned native nodes/relationships/paths also allow graph views. Relationship direction and list/row order are preserved as returned by Neo4j. Correct route order still requires the generated query to use the stored sequence/direction correctly.

Up to 256 displayed nodes and 512 edges are accepted; nested values are limited to eight levels, maps to 128 fields, lists to 512 values and text values to 16000 characters. Oversized first rows fail; later row/byte/graph caps return an explicitly truncated result. Driver-fetched individual records are materialized before these output checks, so the result budget is not a wire-level Neo4j memory limit. Return selected properties and bounded collections for large graphs. Reads are explicitly rolled back after collection, cancelling remaining cursor work without consuming an unbounded result just to commit.

Each row becomes a `RetrievedChunk` containing its JSON for the existing answer plugin. Its source is the configured Neo4j database; `pageNumber=1` is an API compatibility placeholder, not a document page. These are database-row references, **not verified document citations**. They cannot restore source prose absent from the imported graph. The original query, citation preference and graph metadata are retained in `RetrievalResult`.

Lifecycle/progress events report schema loading, generation attempts, validated query hashes and completed row counts. Parameters, source rows, passwords, provider bodies and credentials are not logged by default. Neo4j failures expose a safe error code and remediation, not server query/body text.

CLI connection messages show the configured Neo4j server origin, database and username before connection, then report success only after a read against the selected database. Passwords, URI credentials, URI query parameters and routing context are excluded. A result summary shows returned/populated row counts and graph node/edge counts. `dataStatus` distinguishes `NO_ROWS`, `EMPTY_VALUES` (rows containing only null, blank or recursively empty values) and `DATA`; `populatedRowCount` counts rows with at least one value. Zero and false remain meaningful values. An empty aggregate row is retained but emits a warning: `collect()`/optional matches can produce one result row without matching records. `DATA` does not imply that every part of the question was answered. Verify the exported `NEO4J_DATABASE` as well as the Studio node setting when troubleshooting.

## Read boundary

Generated Cypher is limited to a read-query subset. The lexical validator rejects writes, procedures (including CALL subqueries/APOC), administrative statements, `LOAD CSV`, database switching, comments with unterminated delimiters, multiple statements, unmatched parameters and oversized output. Neo4j `EXPLAIN` must also classify the query `READ_ONLY` before the unchanged query executes with parameters in a READ-routed, timed transaction. Unsupported syntax requires another bounded model attempt or an explicit failure. There is no automatic fallback to another database, provider, KG retriever or unvalidated query.

READ routing and validation do not replace authorization. Use a dedicated read-only database account; available custom database functions and data visibility remain database-admin responsibilities. This plugin intentionally reads an existing externally managed graph under that account, without adding PlugIntell namespace/access-scope predicates. Do not use it as a tenant-security boundary. Expensive matches, cartesian products and aggregations can still consume work before returning rows; transaction timeouts and database resource controls are necessary. Query correctness and factual completeness cannot be guaranteed by schema discovery or read validation.

## Build, export and run

With JDK 21:

```powershell
mvn -pl plugins/neo4j-text2cypher-retrieval,pipeline-builder-api -am package
```

Artifact: `plugins/neo4j-text2cypher-retrieval/target/neo4j-text2cypher-retrieval-plugin.jar`. Restart Studio, add the new retrieval node, save/publish and create a new Java project. Existing published workflows and exported projects are unchanged. The provider client retains its original package/API, so extraction callers remain source-compatible after rebuilding.

Example workflow: [text2cypher-query.json](../../examples/text2cypher-query.json).

```bash
./run.bat --mode query --query "Which stations does Tezgam stop at between Lahore and Multan, in travel order?"
```

Unit tests cover query/parameter validation, denied writes/procedures, READ_ONLY classification, directional graph serialization, row/byte caps, ordering, schema reuse/manual schemas, bounded repair and all three provider HTTP adapters. Generated-application tests verify catalog configuration, selected JAR packaging, provider prompts and Java compilation. These tests use local HTTP and Neo4j-driver fixtures; they do not verify live models or your database.
