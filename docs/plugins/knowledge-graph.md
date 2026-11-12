# Evidence-backed graph extraction and retrieval

These plugins extend the existing Java SPI, Ollama support module and `Neo4jChunkRepository`. They do not add another driver pool, answer generator, ingestion parser, or frontend framework. Two new plugin modules are necessary to provide independently selectable ingestion and query stages. Jackson is the existing project JSON library; the Neo4j support module now also uses it for structured assertion properties and provenance.

| Plugin ID | Input | Output | Workflow |
| --- | --- | --- | --- |
| `ollama-neo4j-graph-extraction` | `ChunkCollection` | `KnowledgeGraphResult` (`KnowledgeGraph.Result`) | Ingestion |
| `ollama-neo4j-graph-retrieval` | `SearchRequest` | `RetrievalResult`, including a `graph` map | Query |

The extraction plugin also accepts one `TextChunk` through direct Java SPI calls. It never opens or rechunks a document. The catalog advertises `ChunkCollection` for typed Studio connections. Existing vector plugins and their unscoped `TextChunk` schema remain unchanged. The existing extraction node now supports a provider selector; its historical plugin ID and display name are retained so existing workflows remain compatible.

## Choose the extraction provider

Open the existing **Ollama Knowledge Graph Extraction** node's settings and choose `LLM_PROVIDER`: `OLLAMA` (default), `OPENAI`, or `GEMINI`. Studio shows the selected provider's fields and required markers using manifest conditions. This extends the existing node; no separate Gemini plugin is installed. Existing published configurations without this field continue using Ollama. Neo4j persistence, chunk/evidence validation, scope and output contracts remain shared across providers. Query graph retrieval continues using its existing Ollama planner.

| Provider | Required model | Required API key | Default server origin | Timeout |
| --- | --- | --- | --- | --- |
| `OLLAMA` | `OLLAMA_CHAT_MODEL` | None | `OLLAMA_BASE_URL=http://localhost:11434` | `OLLAMA_TIMEOUT_SECONDS=60` |
| `OPENAI` | `OPENAI_MODEL` | `OPENAI_API_KEY=secret://env/OPENAI_API_KEY` | `OPENAI_BASE_URL=https://api.openai.com` | `OPENAI_TIMEOUT_SECONDS=60` |
| `GEMINI` | `GEMINI_MODEL` | `GEMINI_API_KEY=secret://env/GEMINI_API_KEY` | `GEMINI_BASE_URL=https://generativelanguage.googleapis.com` | `GEMINI_TIMEOUT_SECONDS=60` |

Model names are free-form provider IDs, not a model allowlist. Gemini names such as `gemini-2.5-flash` and `gemini-2.5-flash-lite` are examples; choose a model accessible to your API key that supports structured outputs. Account access, model retirements and provider quotas remain provider-managed. No automatic model substitution occurs.

Example node configuration (add your existing Neo4j fields and graph policy):

```json
{
  "LLM_PROVIDER": "GEMINI",
  "GEMINI_MODEL": "your-structured-output-model",
  "GEMINI_API_KEY": "secret://env/GEMINI_API_KEY",
  "NEO4J_PASSWORD": "secret://env/NEO4J_PASSWORD"
}
```

Set the selected API-key environment variable in the backend/generated application's process, then save and publish the draft. Rebuild generated projects to capture the new provider setting and plugin code. Secret references are stored; resolved credentials are not. Cloud selection sends chunk text and configured instructions to that provider. Keep provider-specific profiles consistent: switching fields in Studio preserves stored values, and the existing secret resolver still requires every stored secret reference to resolve, including references retained for an inactive provider. Clear unused secret references or keep their environment variables configured.

Gemini uses `/v1beta/models/{model}:generateContent` with JSON schema, a header API key, and model metadata checks for generation/context/output support. OpenAI uses `/v1/chat/completions` with strict `json_schema`, `max_completion_tokens` and `store=false`. Optional graph properties become nullable required fields on the OpenAI wire schema; null optional properties are removed before the unchanged Java validator. Identity properties remain mandatory. OpenAI model metadata verifies model access but does not expose context limits; set `KG_CONTEXT_TOKENS` to a compatible conservative budget. `KG_TEMPERATURE` applies to Ollama/Gemini; OpenAI uses model-default temperature for compatibility with reasoning models.

OPENAI_BASE_URL accepts an HTTPS origin or API base URL such as https://api.example.com/v1/ (with or without the trailing slash). Origin-only URLs default to /v1; explicit API path prefixes are preserved for both model metadata and chat completions, without appending another /v1. Compatible servers must support model metadata lookup and the structured Chat Completions contract described above. GEMINI_BASE_URL accepts an HTTPS origin and the client appends /v1beta. Neither provider accepts URL credentials, query parameters or fragments. HTTP is permitted only for local stub tests. Redirects are disabled, keys are never URL parameters, and provider bodies/keys are omitted from diagnostics. Authentication, model access, quota, timeout, blocked/refused, incomplete-output and byte-limit failures are explicit. Malformed JSON/evidence gets bounded repair attempts; HTTP errors rely on existing execution retries rather than hidden client retries. A successful metadata request does not guarantee model generation access or schema compatibility.

References: [Gemini structured outputs](https://ai.google.dev/gemini-api/docs/structured-output), [Gemini generation API](https://ai.google.dev/api/generate-content), and [OpenAI structured outputs](https://developers.openai.com/api/docs/guides/structured-outputs).

### Gemini HTTP 404 after successful metadata lookup

The adapter identifies whether a 404 occurred in model lookup or `generateContent`, includes the configured model ID and retains only a recognized machine error status such as `NOT_FOUND`. It never prints the provider's error message/body or API key. Successful metadata lookup or a listed `generateContent` capability is not proof of generation access for that API key/project. Check model availability, project access and the Gemini Developer API endpoint; do not assume this requires switching API versions or silently substituting a model. See [Google's troubleshooting guide](https://ai.google.dev/gemini-api/docs/troubleshooting).

Run [test-gemini.ps1](../../tools/test-gemini.ps1) in the same shell/environment as the app, with `GEMINI_API_KEY` set. The diagnostic uses Google's standard `v1beta` Developer API origin. It lists model IDs or checks metadata without source text. `-TestGeneration` explicitly sends one minimal JSON probe, **which may incur provider charges**, and distinguishes metadata success from generation failure. No documents or Neo4j connections are involved.

```powershell
.\tools\test-gemini.ps1 -ListModels
.\tools\test-gemini.ps1 -Model 'your-configured-model' -TestGeneration
```

From Git Bash, call `powershell.exe -NoProfile -File D:/Work/AIProjects/PlugIntell/plugin-pipeline/tools/test-gemini.ps1 -Model your-configured-model -TestGeneration`. Share only its status output when troubleshooting, never the API key. If another model succeeds, select that exact model in Studio, save/publish and rebuild the generated project. Existing generated applications retain their embedded model configuration. The diagnostic intentionally does not choose a replacement model.

If the status alone is inconclusive, add `-ShowProviderMessage`. Because the probe sends no documents, this opt-in prints only Google's `error.message`, with the environment API key and recognizable key strings redacted, control characters removed and output capped at 1500 characters. Full error bodies and headers remain hidden. Windows PowerShell 5 may omit `ErrorDetails`; the diagnostic reads a bounded error-response stream in that case. The pipeline itself continues omitting provider messages. Verify the diagnostic offline with `powershell.exe -NoProfile -File tools/tests/gemini-diagnostic.tests.ps1`.

## Quick start in Studio

1. Build with JDK 21 and Maven 3.9+: `mvn package -DskipTests`.
2. Configure trusted host scope **before** starting Studio:

   ```powershell
   $env:PLUGINTELL_GRAPH_NAMESPACE='research'
   $env:PLUGINTELL_GRAPH_ACCESS_SCOPE='internal'
   # Set NEO4J_PASSWORD securely in this process environment.
   .\run-backend.bat
   ```

3. On a Neo4j 5 database, run [schema.cypher](../../examples/knowledge-graph/schema.cypher) as a schema administrator. The full-text index and lookup indexes must be ONLINE. The extraction node can instead explicitly opt into `KG_PROVISION_SCHEMA=true`; this creates only the fixed KG schema and verifies readiness. If newly populated indexes are not yet ONLINE, wait and retry. The default is verify-only. Retrieval never provisions anything.
4. In Studio, reload the plugin catalog. Import [engineering-project.json](../../examples/knowledge-graph/engineering-project.json) or [heritage-project.json](../../examples/knowledge-graph/heritage-project.json). Replace `replace-with-installed-model`, server URLs and database settings with your own values. Secrets remain environment references.
5. Optionally create a connection profile with entries for the **new plugin IDs**, pinned to their installed versions. The old vector plugins' profile entries are not automatically interchangeable. Mark the profile as default if desired, then select it on graph nodes in Studio.
6. Save, validate and publish. Run ingestion with the sample document, then run the query lane. Published versions capture merged profile settings with secret references, using the existing control service. Drafts remain editable.

```text
Ingestion: Tika parser -> LangChain4j splitter -> Ollama knowledge graph extraction
Query:     Ollama Neo4j graph retrieval -> existing Ollama answer generator
```

The existing AI Studio recommender still targets its standard vector RAG shape. Compose/import these graph workflows in main Studio; no graph-specific recommendation shape is inferred by AI Studio.

### Missing uniqueness constraints

Schema verification accepts both Cypher 5 `UNIQUENESS` and Cypher 25 `NODE_PROPERTY_UNIQUENESS` names, as well as the stronger single-property `NODE_KEY` constraint. Relationship constraints, existence-only constraints and constraints on different/composite properties cannot substitute for node uniqueness on `key`.

If verification lists missing constraints, run the complete schema script in the same database selected by the node's `NEO4J_DATABASE`, or set `KG_PROVISION_SCHEMA=true` on the extraction node using a schema-capable account. Existing vector indexes do not provision the KG schema. In Studio, save and publish the changed draft; rebuild and replace generated applications to include the updated configuration and plugin JARs. Query retrieval remains read-only. Inspect the active database with:

```cypher
SHOW CONSTRAINTS YIELD name, type, entityType, labelsOrTypes, properties
RETURN name, type, entityType, labelsOrTypes, properties;
```

Older plugin builds filtered only the Cypher 5 type name and could falsely report missing constraints on Cypher 25. Rebuild the plugins and generated project when upgrading from those builds; recreating existing constraints is unnecessary in that case. See [Neo4j's constraint listing reference](https://neo4j.com/docs/cypher-manual/5/constraints/list-constraints/).

### Missing or populating lookup indexes

Lookup verification checks node RANGE index labels and the ordered property list, accepting equivalent ONLINE indexes under other names. Diagnostics distinguish `MISSING`, `POPULATING`, `FAILED` and `WRONG_DEFINITION`. The complete schema script includes all four lookup indexes in addition to constraints and full-text search; creating constraints alone is insufficient. Run the complete script against `NEO4J_DATABASE`, wait for its final `CALL db.awaitIndexes(60)` to finish and retry. A failed or conflicting index definition needs administrator review; verification never drops/replaces indexes.

The SLF4J NOP and Log4j missing-provider messages are separate logging warnings. They do not cause KG schema validation failures. For generated applications launched from Git Bash, set the trusted scope in that shell before running either lane:

```bash
export PLUGINTELL_GRAPH_NAMESPACE='ns'
export PLUGINTELL_GRAPH_ACCESS_SCOPE='local'
./run.bat --mode query --query 'Your question'
```

Use the same scope for ingestion and retrieval to see the same graph. Schema provisioning is an explicit extraction-node configuration field, not an override enabled by exporting `KG_PROVISION_SCHEMA` in a shell; existing generated applications must be rebuilt after changing that node configuration.

## Ollama prerequisites and context limits

Use a completion model and Ollama server supporting `/api/chat` with a JSON Schema object in `format`. An embedding model or the Laya decision model is not a substitute for a structured extraction/planning completion model. Model availability/metadata comes from the shared support layer. `/api/show` must confirm `completion` capability and a `model_info.*.context_length` at least as large as `KG_CONTEXT_TOKENS`.

Requests are non-streaming. `temperature`, `num_ctx`, and `num_predict` are explicitly sent. The client bounds response bytes and the whole response deadline, including body consumption. Duplicate JSON keys, trailing JSON, missing content and `done_reason=length` fail validation. Full provider response bodies are not included in errors.

Before sending source text, the plugin reserves the output budget, schema/prompt bytes and 512 framing units against the configured context. Counting UTF-8 bytes as a conservative input-token upper bound is deliberately restrictive, especially for non-ASCII input. Oversized chunks/batches/context fail explicitly; source text is never silently truncated. Reduce upstream chunk size or choose a supported larger context. Model metadata and token estimates cannot establish semantic extraction quality.

## Actual configuration

The complete authoritative schemas, defaults, numeric bounds and conditional requirements are in the [extraction manifest](../../plugins/ollama-neo4j-graph-extraction/src/main/resources/META-INF/PluginManifest.yaml) and [retrieval manifest](../../plugins/ollama-neo4j-graph-retrieval/src/main/resources/META-INF/PluginManifest.yaml). Studio now preserves enum controls, multiline prompt editors, JSON policy editors and `allOf` metadata. The backend validates enums, JSON syntax and scalar `if`/`then` required-field conditions. Cross-field semantic checks also run during plugin configuration/preflight; arbitrary JSON Schema conditionals are not a general-purpose validation engine.

### Shared fields

| Configuration | Default / rule |
| --- | --- |
| `OLLAMA_BASE_URL` | `http://localhost:11434`; no embedded credentials/query/fragment |
| `OLLAMA_CHAT_MODEL` | Required installed completion model |
| `OLLAMA_TIMEOUT_SECONDS` | 60; range 1–600 |
| `NEO4J_URI`, `NEO4J_USERNAME`, `NEO4J_DATABASE` | `bolt://localhost:7687`, `neo4j`, `neo4j` |
| `NEO4J_PASSWORD` | Required secret reference in Studio; resolved only at runtime |
| `KG_FULLTEXT_INDEX` | `plugintell_kg_entities`; must equal host `PLUGINTELL_KG_FULLTEXT_INDEX` if overridden |
| `KG_TEMPERATURE` | 0; range 0–1 |
| `KG_CONTEXT_TOKENS`, `KG_OUTPUT_TOKENS` | 16384 / 2048; output must be smaller than context |
| `KG_RESPONSE_BYTES` | 262144; range 1024–2097152 |
| `KG_REPAIR_RETRIES` | 1 additional malformed-output attempt; hard maximum 3 |
| `KG_QUERY_TIMEOUT_SECONDS` | 20; range 1–120, Neo4j transaction timeout plus application read deadline |
| `KG_DOMAIN_INSTRUCTIONS`, `KG_PROMPT_TEMPLATE` | Optional additional trusted instructions, at most 8000 characters each |
| `KG_ENTITY_TYPES`, `KG_RELATIONSHIP_TYPES` | Comma-separated semantic allowlists, at most 64 entries each |

Database connection/routing establishment also has the shared Neo4j driver's finite connection/retry settings. Model calls are never inside database transactions. Query-time data cannot override indexes, database, credentials, scope or resource budgets. Changing the host-approved full-text index requires explicitly provisioning the matching schema; it is not a query-plan option.

### Extraction fields

| Configuration | Default / behavior |
| --- | --- |
| `KG_SCHEMA_MODE` | `OPEN`; `STRICT` requires both type allowlists |
| `KG_PROPERTY_SCHEMAS` | JSON object `{}`: no optional entity/fact properties are permitted by default |
| `KG_RELATIONSHIP_CONSTRAINTS` | JSON array `[]` of `{source, relationship, target}` semantic type triples |
| `KG_IDENTITY_POLICY` | `CHUNK_EVIDENCE` or explicit `PROPERTY` |
| `KG_IDENTITY_PROPERTY` | Required permitted string property in `PROPERTY` mode; display names/aliases cannot be identity fields or identity values |
| `KG_NORMALIZATION` | `NFKC_CASEFOLD`, or case-sensitive `NFKC` for identity names |
| `KG_PROVISION_SCHEMA` | false; explicit writer-only schema administration opt-in |
| `KG_FAILURE_POLICY` | `FAIL` or `CONTINUE` with structured per-chunk failures |
| `KG_CONCURRENCY` | 1; range 1–8 concurrent extraction calls, bounded scheduling blocks |
| `KG_MAX_BATCH_CHUNKS`, `KG_MAX_INPUT_CHARS` | 256 / 6000; reject oversized input |
| `KG_MAX_ENTITIES`, `KG_MAX_RELATIONSHIPS` | 64 / 128 per chunk |
| `KG_MAX_ALIASES`, `KG_MAX_NAME_CHARS` | 8 / 240 per entity; normalized lookup keys also have a 1024-byte safety cap |
| `KG_MAX_PROPERTIES`, `KG_MAX_EVIDENCE_CHARS` | 16 / 2000 per record |

`OPEN` is not arbitrary executable schema: semantic types remain bounded identifiers and properties remain allowlisted. `STRICT` additionally restricts both semantic type vocabularies. Allowed property schemas support scalar `string`, `integer`, `number`, `boolean`, `enum`, `maxLength`, `minimum`, and `maximum`. Nested values, arrays, unknown schema keywords, unknown property keys and out-of-range values are rejected. Property literals must occur in the record's evidence quote; this conservative check does not infer boolean concepts or normalize numeric units.

For example:

```json
{
  "identifier": {"type":"string", "maxLength":100},
  "rating": {"type":"integer", "minimum":0, "maximum":100}
}
```

Custom prompts are appended to a fixed system contract. The request's JSON schema, response field set, identity/scope rules, counts, type/property validation and evidence verification remain enforced in Java. A prompt override cannot permit Cypher, new physical labels, authorization fields or unsupported evidence. Source text is supplied as a separate untrusted JSON user message. Repair requests do not echo unvalidated provider output back into trusted instructions.

### Retrieval fields

| Configuration | Default / behavior |
| --- | --- |
| `KG_STRATEGY` | `LEXICAL`: exact aliases plus full-text candidate RRF |
| `KG_VECTOR_FALLBACK` | `FAIL`, or explicit `LEXICAL` downgrade for unsupported VECTOR/HYBRID |
| `KG_VECTOR_INDEX`, `KG_EMBEDDING_MODEL` | Reserved references; do not activate vector retrieval |
| `KG_PLANNING_FALLBACK` | `LEXICAL`, or `FAIL` |
| `KG_SEED_CANDIDATES`, `KG_RESULT_COUNT` | 32 per channel / 10 fused seeds, also capped by `SearchRequest.topK` |
| `KG_DEFAULT_HOPS`, `KG_MAX_HOPS` | 2 / 3; hard maximum 3 |
| `KG_DIRECTION` | `BOTH`, `IN`, or `OUT`; a plan cannot widen a restrictive direction |
| `KG_MAX_EXPANDED_NODES`, `KG_MAX_EXPANDED_RELATIONSHIPS` | 128 / 512; the relationship budget limits physical candidates explored as well as emitted facts |
| `KG_MAX_PATHS`, `KG_MAX_EVIDENCE_CHUNKS` | 128 / 20 |
| `KG_MAX_NODE_DEGREE` | 32 physical candidates per expanded node, including rejected candidates |
| `KG_CONTEXT_CHARS` | 24000 characters for serialized graph/evidence/chunk context, excluding plan/diagnostic envelope |
| `KG_FUSION_K` | 60 in reciprocal-rank fusion |

Vector graph search is **not implemented**. Existing `TextChunk` vectors have neither verified embedding-model identity nor the scoped graph-entity vector/index contract needed here. Dimensions alone are insufficient. VECTOR/HYBRID fail explicitly by default; only an explicitly selected lexical fallback proceeds and reports degradation. No vector index is provisioned or silently reused.

## Graph representation and provenance

All physical identifiers are application constants. The graph uses:

```text
KgAlias -KG_ALIAS_OF-> KgEntity
KgChunk -KG_SUPPORTS-> KgEvidence -KG_ASSERTS-> KgEntity or KgFact
KgFact  -KG_SOURCE-> KgEntity
KgFact  -KG_TARGET-> KgEntity
KgChunk -KG_ORIGIN-> legacy TextChunk (only if a matching legacy node already exists)
KgRevision: namespace/access-scope data revision counter
```

Semantic entity/relationship types are string properties, never model-generated physical labels or relationship types. Optional extracted properties are stored as `propertiesJson` on individual evidence nodes; model property keys are never interpolated into Cypher. All model/source values are parameters. The small KG adapter receives the repository's existing `Driver` and database settings, without owning another connection lifecycle.

`scopeKey = SHA256(namespace + newline + accessScope)`. Entity and fact keys include that scope key. Fact identity includes source identity, semantic relation type and target identity. The default `CHUNK_EVIDENCE` entity identity additionally includes document ID, original chunk ID, semantic type, normalized name and quote start. Identical names in different chunks therefore remain distinct candidates. `PROPERTY` identity uses an explicitly configured, quoted identifier and semantic type within scope; it is not automatic fuzzy/coreference resolution. Incorrect source identifiers can still cause incorrect identity links.

Every entity and fact must have a verbatim quote. Java calculates UTF-16 offsets into the original chunk text, using the supplied zero-based occurrence for repeated quotes. Entity names, aliases and property literals must appear as bounded literals in their entity quote; relationship quotes must mention both canonical endpoints. This validates anchoring and structure, not logical entailment: a model can still misinterpret negation, modality or domain semantics. Returned relationships are labeled stored assertions rather than proven truths.

### Replacement, conflicts and retries

Existing splitter chunk IDs contain text hashes. A `KgChunk` replacement key therefore uses **scope + document ID + page number + ordinal**; its `originalId` tracks the current actual chunk ID. Input collections must have unique chunk IDs and unique page/ordinal slots. A changed chunk replaces that slot even when its content-derived chunk ID changes.

Each chunk is one transaction, serialized through the scope revision record. A deterministic extraction-content revision includes source text, validated records and model/schema/prompt provenance, but excludes run identity. An identical replay does not create entities, facts or evidence again, nor advance the data revision. Observed counts describe the validated response; created counts come from Neo4j counters on the specific entity/fact/evidence MERGEs, excluding aliases, chunks and revision metadata.

Replacement deletes only that slot's old evidence, inserts new evidence, removes facts/entities left without any supporting assertions, and rebuilds affected entities' searchable names from surviving evidence. Shared facts retain other supporting chunks. Entity display names are selected from the lexicographically first active evidence ID; normalized aliases are the union of current evidence names/aliases. Raw asserted names/aliases remain on their evidence records. An aggregate active alias set above 1024 rolls the transaction back. Unreferenced alias dictionary nodes may remain, without active entity links.

Properties from different sources never overwrite one canonical property map: they remain separate evidence-level assertions, including conflicting values. Consumers must inspect provenance to resolve conflicts. Evidence records capture model, schema hash, prompt hash, scope, original chunk/document/source/page identity and available run/pipeline/version/node metadata. Identical replay retains the original supporting write's provenance; the returned extraction result and normal execution record carry the current observation's run/version. Content/evidence updates are atomic per chunk, not per document or entire batch.

An empty valid extraction retracts that slot's prior assertions. A failed extraction does not retract evidence. In `CONTINUE`, the result explicitly lists failures and warns that failed slots keep their prior evidence. Configuration/scope/model-prerequisite failures still fail the node. Node-level retries and cancellation use the existing execution policy; HTTP work is interruptible, outstanding extraction futures are cancelled, and database operations check interruption and transaction deadlines.

**Deletion limitation:** a partial `ChunkCollection` does not identify deleted/trailing document slots. Missing slots are not automatically retracted. Full document deletion, reordering or rechunking that removes old slots requires an explicit host/document lifecycle retraction policy; do not treat an arbitrary partial batch as a complete document snapshot. No historical graph snapshot is created by publishing a pipeline. Graph revisions aid diagnosis, but mutable graph/index/model changes mean replay is not historically reproducible. A read reports start/end graph revisions and warns if they differ; Neo4j read-committed isolation is not a historical snapshot guarantee.

## Retrieval and ranking

1. Scan at most 2000 scoped records in each semantic schema channel. Give Ollama a bounded structured plan schema with names, aliases, semantic text, allowed/available types, direction and hops. Reject extra fields, unknown types or widened limits. Empty type filters are valid. Invalid/absent planning either fails or uses the configured lexical fallback, with an explicit warning and `planningMode`.
2. Find scoped exact aliases and full-text matches. Lucene input is tokenized and quoted in application code; a scope-key clause is mandatory. Each channel is capped before fusion. RRF uses `sum(1 / (KG_FUSION_K + rank))`; these scores are not probabilities. Distinct identities with the same name remain ambiguous candidates. Candidate/type/active-evidence filtering can leave fewer results when a channel cap is reached; diagnostics report capped seed work.
3. Use application BFS over fixed one-hop templates, never unrestricted text-to-Cypher or a variable-length path query. Count physical adjacency candidates **before** semantic/access filtering. Bound global explored relationships, expanded/returned nodes, per-node degree and path count. BOTH splits its degree budget between directions. Scope and active evidence are checked on seeds, endpoints, facts and source evidence. An unauthorized intermediate entity is never queued. Each node is expanded at most once; returned paths avoid repeated nodes and are a bounded shortest-path sample, not exhaustive paths.
4. Path/fact relevance decays as `seedRrf / (1 + hopCount)` after semantic relationship filters. Nodes, facts, paths, source chunks and evidence IDs are deduplicated. A multi-hop path never creates an inferred direct edge. At most eight evidence records per subject are read in evidence-key order; the composite scope/subject/key index supports bounded ordered lookup.
5. Admit whole source chunks and assertions within graph/evidence/context budgets. An exact final serialized-context check prevents estimate overruns; if necessary it returns empty context with truncation diagnostics rather than dangling/unsupported assertions. Context excludes the separate question/plan/diagnostic envelope. Empty and partial results are explicit, with no invented facts.

All graph retrieval uses `AccessMode.READ`, `executeRead`, fixed read templates and transaction/application deadlines. It performs no graph mutations, including during preflight. Provisioning and graph extraction are separate write paths.

## Payload and examples

- [Engineering source](../../examples/knowledge-graph/engineering.txt), [chunk](../../examples/knowledge-graph/engineering-chunk.json), [model extraction](../../examples/knowledge-graph/engineering-extraction.json), [project](../../examples/knowledge-graph/engineering-project.json).
- [Heritage source](../../examples/knowledge-graph/heritage.txt), [chunk](../../examples/knowledge-graph/heritage-chunk.json), [model extraction](../../examples/knowledge-graph/heritage-extraction.json), [project](../../examples/knowledge-graph/heritage-project.json).
- [Illustrative retrieval result](../../examples/knowledge-graph/retrieval-result.json): opaque evidence IDs, hashes/timings and condensed evidence are illustrative; actual results include admitted entity and fact evidence and runtime diagnostics.

These are Java payload examples, not new document/chunk REST upload endpoints. Programmatic input remains the existing SPI:

```java
new ChunkCollection("equipment-manual", List.of(
    new TextChunk("chunk-001", "equipment-manual", "equipment.txt", 1, 0,
        "Atlas component C-17 powers Boreal component C-18.")));
new SearchRequest("What does Atlas power?", true, 5);
```

Extraction returns `KnowledgeGraph.Result`: validated records, processed original chunk IDs, per-chunk write statistics, warnings, chunk failure codes and model/schema/prompt/execution provenance. Retrieval returns the original question, citation chunks and `RetrievalResult.graph`: validated plan, ranked nodes/relationships/paths, exact supporting evidence, ranking method, actual strategy, planning mode/timing and truncation/fallback/revision diagnostics. Existing answer generation can consume the citation chunks without another adapter. No final answer is produced by retrieval itself.

Default telemetry/Studio execution summaries expose counts, failures and safe provenance rather than full source text or provider bodies. Standalone graph result `toString()` methods are also summary-only. Full records remain available to explicit SPI consumers. Native Image with these new structured-record serializers has not been verified.

## Security boundary

`PluginMessages.executionContext()` is an optional, backward-compatible host context API. The normal attempt reporter adds run, node, pipeline ID and published version. Scope is read from the host process's `PLUGINTELL_GRAPH_NAMESPACE` and `PLUGINTELL_GRAPH_ACCESS_SCOPE`; neither has a permissive default. Plain/no-op reporters can supply trusted process scope but have no published/run provenance. Tests may inject a trusted reporter explicitly. Do not construct this context from source text, query parameters or node configuration.

This is fixed host-scope isolation, **not per-user multi-tenancy**. All authorized executions in one host process share that configured scope. The existing RBAC decides who may execute/edit; deployments needing different user scopes must use a trusted host integration or separate scoped processes/database accounts. Direct database writers and installed Java plugins remain trusted. Neo4j credentials need appropriate read/write privileges; use a read-only account for graph retrieval. No application filter can defend against an administrator bypassing its repository/schema rules directly in the database.

Published configuration and profiles contain references such as `secret://env/NEO4J_PASSWORD`, never resolved credentials. Runtime secret resolution stays in the existing wrappers/services. Do not put credentials into URL userinfo, prompts, property schemas or sample files. Source text and queries are untrusted even when they contain instructions; semantic JSON is validated and never executed as database code.

## Verification

Routine tests use local HTTP stubs and deterministic reader fixtures; they do not require cloud credentials, Ollama or Neo4j. Provider fixtures cover Gemini/OpenAI headers and schemas, configurable model IDs, malformed-output repairs, optional-property conversion, authentication/quota failures, timeouts and response limits. Repository read tests assert read sessions/transactions and parameterized, scoped fixed queries. Opt-in Neo4j tests exercise actual transactional replacement, shared evidence, changed content IDs, namespace isolation, an unauthorized intermediate node and read revision stability. The repository previously had driver/configuration tests but no embedded/container Neo4j harness, so these tests use the existing Java driver against an explicitly configured disposable Neo4j 5 database; no new test-container dependency is introduced.

```powershell
mvn -pl pipeline-builder-api -am test
mvn test
mvn package -DskipTests
node --check pipeline-builder-ui/studio.js
node --test pipeline-builder-ui/studio.test.cjs
```

To opt into Neo4j integration checks, use a disposable database and provide its test credentials in the process environment:

```powershell
$env:KG_TEST_NEO4J_URI='bolt://127.0.0.1:7687'
$env:KG_TEST_NEO4J_USERNAME='neo4j'
$env:KG_TEST_NEO4J_DATABASE='neo4j'
# Set KG_TEST_NEO4J_PASSWORD securely in this shell.
mvn -pl plugins/neo4j-plugin-support -am test '-Dtest=KnowledgeGraphNeo4jIntegrationTest' '-Dsurefire.failIfNoSpecifiedTests=false'
```

Opt-in real-model smoke test, in addition to the Neo4j variables above:

```powershell
$env:KG_LIVE_OLLAMA_URL='http://YOUR_OLLAMA_HOST:11434'
$env:KG_LIVE_OLLAMA_MODEL='YOUR_INSTALLED_COMPLETION_MODEL'
$env:KG_LIVE_CONTEXT_TOKENS='16384' # Must be supported by model metadata.
mvn -pl plugins/ollama-neo4j-graph-retrieval -am test '-Dtest=GraphLiveSmokeTest' '-Dsurefire.failIfNoSpecifiedTests=false'
```

These opt-in tests explicitly provision the fixed schema and use a unique fixture namespace. They clean up their scoped records, but intentionally leave schema objects. They are skipped when the required environment variables are absent; skipped live checks are not evidence of database/model compatibility.

Conceptual references: [Ollama structured outputs](https://docs.ollama.com/capabilities/structured-outputs), [Neo4j GraphRAG retrieval guide](https://neo4j.com/docs/neo4j-graphrag-python/current/user_guide_rag.html), and [GraphAcademy knowledge-graph construction](https://graphacademy.neo4j.com/courses/workshop-genai/2-knowledge-graph-construction/1-knowledge-graph-construction). The GraphAcademy lesson's detailed content requires sign-in. These are adapted concepts; this implementation uses the repository's Java contracts rather than the Python GraphRAG package.
