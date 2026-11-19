# Neo4j Text-to-Cypher Retrieval

`neo4j-text2cypher-retrieval` (display name **Neo4j Text-to-Cypher Retrieval**) reads existing, externally ingested Neo4j graphs. It accepts `SearchRequest` and returns the existing `RetrievalResult`. It does not require `KgEntity`, `KgFact`, full-text/vector indexes, an ingestion lane, or APOC. It never provisions database schema or writes graph data.

## Workflow

```text
Question -> schema/constraints + question hints -> Cypher + parameters
         -> host-derived path metadata (or extended model intent for other operations)
         -> local read/semantic checks -> schema-backed anchor resolution
         -> Neo4j EXPLAIN -> bounded read -> native-path/part-coverage evaluation
         -> up to two structured-feedback repairs -> complete/partial/empty RetrievalResult
         -> answer plugin (optional) -> Universal Response Renderer (optional)
```

The model generates the query, not the answer. Connect the existing Ollama Answer plugin after retrieval for prose grounded in returned rows. That answer plugin has its own provider/model configuration; selecting OpenAI or Gemini for Cypher generation does not change the answer node. Connect directly to Universal Response Renderer to inspect rows and returned graph entities instead.

This follows the same Text-to-Cypher approach as [LangChain4j's Neo4j retriever](https://github.com/langchain4j/langchain4j/blob/main/docs/docs/integrations/embedding-stores/neo4j.md), but reuses PlugIntell's existing structured Ollama/OpenAI/Gemini clients and Neo4j driver. It does not add or instantiate the community `Neo4jText2CypherRetriever`, and it is not a general loader for arbitrary `ChatModel` implementations. The shared provider client retains its existing Java class name and package, `com.core.docintel.llm.ExtractionModelClient`, and now lives in `neo4j-plugin-support` for reuse without depending on an extraction plugin.

## Is the plugin generic?

Yes. The plugin is reusable across Neo4j graph domains. Production logic does not contain train/station names, railway relationship names, or a fixed railway query. The railway dataset is a regression/example fixture. The model uses the connected database's labels, properties, directed patterns and constraints to propose a query for that graph.

The following are example applications, provided the corresponding facts and connections exist in the graph; they are not a claim that every domain/question has been live-tested:

| Graph domain | Example graph structure | Example questions |
| --- | --- | --- |
| Project management | `Project`, `Task`, `OWNS`, `DEPENDS_ON` | Which tasks depend directly on this task? What is the dependency path between two tasks? |
| Organization | `Person`, `Team`, `REPORTS_TO`, `MEMBER_OF` | Who reports to this manager? How many people belong to each team? |
| Commerce | `Customer`, `Order`, `Product`, `PLACED`, `CONTAINS` | Which products did a customer buy? Count orders matching a date/status filter. |
| Supply chain | `Supplier`, `Warehouse`, `Product`, `SUPPLIES`, `SHIPS_TO` | Which suppliers provide a product? Which warehouses are connected along a shipment path? |
| History | `Person`, `Event`, `Place`, `PARTICIPATED_IN`, `OCCURRED_AT` | Which events involved a person? Where did those events occur? |

To use another dataset:

1. Ingest that dataset into Neo4j using a separate ingestion plugin or external tool. This retrieval plugin does not ingest a PDF, text file or CSV supplied as query text.
2. Configure the correct `NEO4J_URI`, `NEO4J_DATABASE` and read-only credentials.
3. Allow schema introspection, or supply a matching `T2C_SCHEMA_JSON` when needed. A manually attached schema is optional, not required for every pipeline.
4. Add `T2C_INSTRUCTIONS` for business meanings that labels/property names cannot establish, such as edge direction, ownership rules, tenant boundaries or the meaning of an ordering property. Instructions do not replace database authorization or automatically add access-control predicates.
5. Ask questions whose evidence exists in that graph. Add an answer plugin for prose, or combine with document/vector retrieval in a Hybrid RAG pipeline for information outside the graph.

Generic does not mean every question is automatically answerable. The supported read-query subset covers lookups, filtering, direct neighbors, bounded paths and aggregations. Generated procedures, writes and administration are rejected. Initial question hints are English heuristics; compact path metadata and name normalization support identifiable query shapes, not every Cypher expression or language. Schema samples, ambiguous identities and model mistakes can still prevent an answer. A relationship's business meaning and a recommendation's supporting evidence cannot be invented from graph structure alone.

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

By default, the plugin reads the [built-in schema procedures](https://neo4j.com/docs/operations-manual/current/procedures/built-in-procedures/) `db.schema.nodeTypeProperties()` and `db.schema.relTypeProperties()`, samples directed relationship patterns, and reads `SHOW CONSTRAINTS` metadata including uniqueness keys. Repeated property rows are grouped before sending the schema to the model, preserving types/directions/constraints while reducing prompt size. Property procedures may inspect substantial data: the transaction timeout bounds runtime, not total database work. The account needs permission for these built-in read procedures and constraint inspection. Pattern samples are explicitly marked as samples, not a complete inventory. Schema metadata exceeding `T2C_SCHEMA_LIMIT` or 32000 characters fails with guidance instead of silently truncating.

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

## Request-to-result walkthrough

### 1. Receive the question and check configuration

The pipeline supplies `SearchRequest`, for example:

```java
new SearchRequest(
    "I'm traveling from Rawalpindi to Karachi on Tezgam. " +
    "Which stations will I pass through between Lahore and Multan, " +
    "in the correct order, and why might this train be a good choice for my family?",
    false
);
```

The plugin rejects input of another type or a question longer than 4000 characters. Configuration selects the database, provider, model, limits and optional domain guidance. Preflight verifies a read against the selected Neo4j database, loads its schema and checks model availability/capacity. Java owns the Neo4j driver and credentials; the model does not select the server/database or receive database credentials from the request builder.

### 2. Interpret the question locally

`SemanticPlan.hints()` performs pattern matching in Java, without another LLM completion. In the example it separates:

| Field | Value |
| --- | --- |
| Overall journey | Rawalpindi to Karachi |
| Requested path endpoints | Lahore to Multan |
| Named subject/context | Tezgam |
| Retrieval operation | Ordered path |
| Intermediate-only display | Yes |
| Descriptive component | Why suitable for a family? |

The distinction prevents overall journey endpoints from replacing the requested sub-route. The current `between A and B` heuristic normally excludes endpoints unless the question explicitly asks for inclusive endpoints. Phrases such as `from ... to ... on/using/via ...` can supply the subject/context; this is a heuristic, not general entity recognition or universal language understanding.

### 3. Build the system prompt and model-facing task

Recognized path questions use the compact contract. The model is asked to return only `cypher` and `parameters`; Java derives interpretation/coverage metadata locally. Other operations retain the extended `cypher`, `parameters`, `intent` contract described below.

The system prompt contains generation rules. Representative instructions from the path prompt are:

```text
Generate one parameterized Neo4j 5 READ query.
Return ONLY JSON with cypher and parameters.
Use retrievalTask.pathStart and retrievalTask.pathEnd as endpoint values.
Constrain retrievalTask.subject when a subject is specified.
Use actual schema labels, properties and directed connections.
Bind a native path and return it AS path, not nodes($path) or endpoint lists.
Use a literal hop bound within the configured maximum.
Never alphabetize path nodes or manufacture descriptive facts.
Do not generate writes, procedures, administration or multiple statements.
```

`T2C_INSTRUCTIONS` is included as domain guidance and cannot override read-only validation. Scoped questions get one generic owner/member syntax example with placeholder labels/types; questions without a subject get an unscoped example. The placeholders are instructions to adapt to the actual schema, not railway-specific Cypher executed by Java.

The separate user message is serialized JSON. This is a shortened, illustrative first-attempt payload; actual schema fields/types/constraints come from introspection or the manual schema:

```json
{
  "schema": {
    "nodes": [
      {"labels": ["Train"], "properties": {"name": "STRING"}},
      {"labels": ["Station"], "properties": {"name": "STRING"}}
    ],
    "relationships": [
      {"type": "STOPS_AT"},
      {"type": "NEXT_STOP"}
    ]
  },
  "directedSchemaPatterns": [
    "(Train)-[:STOPS_AT]->(Station)",
    "(Station)-[:NEXT_STOP]->(Station)"
  ],
  "feedback": {},
  "retrievalTask": {
    "operation": "ORDERED_SCOPED_PATH",
    "pathStart": "Lahore",
    "pathEnd": "Multan",
    "subject": "Tezgam",
    "subjectMatchRequired": true,
    "return": "native path including endpoints; host selects intermediate nodes when requested"
  }
}
```

The full question is preserved in Java and `RetrievalResult`. Initial path-generation input focuses on the requested sub-route rather than presenting overall journey endpoints as competing bindings. Repair feedback may contain the original interpretation and previous query. If suitable non-structural properties exist, the task can also include the optional descriptive request and candidate properties; their presence alone does not prove suitability.

### 4. Send the request to the configured model

For Ollama, `OllamaStructuredClient` sends `POST {OLLAMA_BASE_URL}/api/chat` with:

| Field | Value/purpose |
| --- | --- |
| `model` | Configured `OLLAMA_CHAT_MODEL` |
| `messages` | System prompt plus JSON user-message content |
| `format` | JSON Schema defining `cypher` and the `parameters` array |
| `stream` | `false` |
| `options.temperature` | Configured temperature; default `0` |
| `options.num_ctx` | Configured context budget; default `16384` |
| `options.num_predict` | Configured output budget; default `2048` |

Ollama's separate `/api/show` call verifies model capability/context metadata. It is not a generation request. OpenAI and Gemini use their corresponding structured-output HTTP adapters; changing the provider does not change the retrieval SPI or give the model database access.

### 5. Receive a candidate query with separate parameters

For the illustrative schema, a suitable candidate could be:

```json
{
  "cypher": "MATCH (t:Train {name:$subject})-[:STOPS_AT]->(a:Station {name:$start}), (t)-[:STOPS_AT]->(b:Station {name:$end}) MATCH p=(a)-[:NEXT_STOP*1..64]->(b) RETURN p AS path LIMIT 100",
  "parameters": [
    {"name": "subject", "value": "Tezgam"},
    {"name": "start", "value": "Lahore"},
    {"name": "end", "value": "Multan"}
  ]
}
```

This is an example of model output, not a fixed production query. The `STOPS_AT` matches scope the endpoints to the train; `NEXT_STOP*1..64` is the bounded traversal. `p` is a query variable containing a native path, while `$start`, `$end` and `$subject` are parameters. The model has proposed text only; Java has not yet executed it.

### 6. Normalize, validate and resolve names

Java logs the original candidate before validation, derives compact path metadata and applies only the narrow normalizations documented below. It checks allowed operations, parameter definitions/references, path limits, endpoint interpretation, subject presence and schema-backed anchors. A field called `path` is not sufficient evidence that a native path exists.

An anchor maps a human mention to a schema property and a query parameter, such as `SUBROUTE_START -> Station.name -> $start`. Bounded database reads find candidates. If one unambiguous match exists, Java can replace `Lahore` with `Lahore Jn.` and `Multan` with `Multan Cantt`. Multiple candidates are not arbitrarily selected as canonical values. Subject identity corrections require the exact database evidence described below. These lookups are Neo4j reads, not extra LLM completions.

### 7. Explain, execute and evaluate the result

Java submits `EXPLAIN` with the candidate and its parameters. Neo4j must accept the query and classify it `READ_ONLY` before the unchanged query executes in a timed read transaction. Query results are bounded by row, byte and graph-display limits. `EXPLAIN` validates syntax/read classification, not the business meaning of the question.

For an ordered path, the driver must return an actual Neo4j `Path`. Java records its provenance separately from ordinary maps/lists, checks connectivity, direction and requested endpoints, and projects selected nodes in native traversal order. It can clip a returned full path to the requested sub-route and exclude endpoints for intermediate-only questions. It does not alphabetically sort a route.

Coverage is evaluated independently. In the railway fixture, seven intermediate stations can be answered while family suitability remains unanswered. The result is `PARTIAL`, not an invented recommendation. Structural endpoint/name facts do not establish qualitative benefits.

### 8. Correct failures within the configured budget

A correctable failure supplies structured feedback: failed checks, prior query/parameters, canonical-name candidates, original interpretation and any earlier successful parts. The model then receives another generation request. Each attempt still goes through the same validation and evidence checks.

`KG_REPAIR_RETRIES=1` means at most two generations total; `2` means at most three. The maximum is two repairs, not two repairs per validation stage. Existing exported applications retain their saved configuration even when a new manifest default changes. Later failures do not erase earlier successful partial reads. If no acceptable read was produced, the plugin fails explicitly.

### 9. Return retrieval evidence to the pipeline

The output is the original `RetrievalResult` type, containing the original question/citation preference, retrieved chunks, rows, nodes/edges, verified paths, intent, coverage, unanswered parts, attempts and warnings. The viewer can display those results directly. An answer plugin can synthesize prose from them, and Hybrid RAG can add other retrieval evidence for unanswered components.

A successful first attempt uses one model completion. Schema discovery, model metadata checks, name resolution and Neo4j reads are additional service requests but not additional LLM completions. Corrections and an optional downstream answer plugin can add model calls.

Implementation entry points: [plugin lifecycle](../../plugins/neo4j-text2cypher-retrieval/src/main/java/com/core/docintel/text2cypher/Neo4JText2CypherRetrievalPlugin.java), [path prompting](../../plugins/neo4j-text2cypher-retrieval/src/main/java/com/core/docintel/text2cypher/PathQueryContract.java), [question interpretation](../../plugins/neo4j-text2cypher-retrieval/src/main/java/com/core/docintel/text2cypher/SemanticPlan.java), [repair orchestration](../../plugins/neo4j-text2cypher-retrieval/src/main/java/com/core/docintel/text2cypher/SemanticRetrieval.java), and [Neo4j reads/EXPLAIN](../../plugins/neo4j-text2cypher-retrieval/src/main/java/com/core/docintel/text2cypher/Neo4jReadGraph.java).

## Limits and diagnostics

| Setting | Default | Meaning |
| --- | --- | --- |
| `T2C_SCHEMA_LIMIT` | 256 | Maximum metadata rows per category and sampled edge budget |
| `T2C_SCHEMA_TTL_SECONDS` | 300 | Schema cache lifetime within a plugin instance; zero disables caching |
| `T2C_MAX_ROWS` | 100 | Maximum returned rows; independent of `SearchRequest.topK` so a 31-stop route is not reduced to five rows |
| `T2C_MAX_PATH_HOPS` | 64 | Maximum literal variable-length upper bound, configurable from 1 to 128 |
| `T2C_MAX_RESULT_BYTES` | 262144 | Conservative accepted-result budget, including presentation overhead |
| `KG_QUERY_TIMEOUT_SECONDS` | 20 | Per-read-transaction deadline, at most 120 seconds |
| `KG_CONTEXT_TOKENS` | 16384 | Conservative UTF-8 prompt/schema budget; input is not silently truncated |
| `KG_OUTPUT_TOKENS` | 2048 | Model generation budget |
| `KG_RESPONSE_BYTES` | 262144 | Provider HTTP response limit |
| `KG_REPAIR_RETRIES` | 2 | At most two repairs after structured validation/result feedback; runtime clamps legacy values above two |
| `T2C_LOG_CYPHER` | true | Generated query logging with known secret values redacted; explicit false retains query privacy |

Results include ordered `rows`, normalized `nodes`/`edges`, verified `paths`, renderer blocks, provider/model/database, query hashes, duration, warnings and a truncation flag. `intent` describes the candidate interpretation (derived locally for compact path responses, or supplied through the extended model contract); `semanticStatus`, `coverage` and `unansweredParts` describe accepted results, rather than assuming the proposal was correct. `attempts` records reason codes, attempt outcomes and query hashes. These metadata additions preserve the existing `SearchRequest -> RetrievalResult` API.

Up to 256 displayed nodes and 512 edges are accepted; nested values are limited to eight levels, maps to 128 fields, lists to 512 values and text values to 16000 characters. Oversized first rows fail; later row/byte/graph caps return an explicitly truncated result. Driver-fetched individual records are materialized before these output checks, so the result budget is not a wire-level Neo4j memory limit. Return selected properties and bounded collections for large graphs. Reads are explicitly rolled back after collection, cancelling remaining cursor work without consuming an unbounded result just to commit.

Each accepted row becomes a `RetrievedChunk` with a JSON `data` field. The first chunk also includes retrieval status and unanswered parts so an existing answer plugin does not need a new API to see partial-answer limitations. Hybrid RAG consumers can combine these chunks with other retrieval plugins and inspect structured `coverage`/`unansweredParts`. Sources identify the configured Neo4j database; `pageNumber=1` is an API compatibility placeholder, not a document page. These are database-row references, **not verified document citations**. They cannot restore source prose absent from the imported graph. The original query, citation preference and graph metadata are retained.

Logs report interpreted intent, generated Cypher (unless explicitly disabled), sanitized parameter summaries, validation outcomes, row/path counts, structured repair reasons and final execution status. Every structured LLM candidate is logged **before local validation**, marked `unvalidated` with its attempt number, so rejected queries remain visible. Cypher is shown up to the accepted query limit of 16000 characters, with an explicit marker if oversized; rejected forbidden tokens include their name and one-based character position. `T2C_LOG_CYPHER=false` also disables pre-validation query logging. Sensitive parameter names (including name/value parameter arrays) and known configured secret values are redacted; long parameter/intent values are capped. Source rows, passwords, authentication headers and provider/server error bodies are not logged. Neo4j failures expose only a safe error code. `EXPLAIN` success is explicitly logged as syntax/read classification, never semantic proof.

## Semantic evaluation and partial answers

For questions recognized as path requests by the existing question hints, all three provider adapters request a compact structured response containing only `cypher` and `parameters`. The model no longer has to generate a parallel intent/anchors/coverage document. Java derives path intent from the question hints, actual schema-backed parameter predicates, returned path column and projected descriptive attributes. Anchors use actual parameter values, not names of hint fields. Unsupported descriptive requests are explicitly retained as unanswered; unrequested counts are not presented as evidence of qualitative suitability. Native path evidence and all existing validation remain required. The compact prompt preserves configured domain instructions and does not add another model call or change the chosen model.

The model-facing path input is a focused `retrievalTask`: requested path start/end, named subject when present, and native-path output. The complete compound question and overall journey endpoints stay in the host and original `RetrievalResult`; they are not competing query endpoints in the model input. Optional descriptive requests are included only alongside candidate non-structural attributes actually present in the schema. Directed patterns are rendered from discovered `observedPatterns` or configured relationship source/type/target metadata, with no railway-specific labels or edge names in production logic. For named subjects the prompt uses a single generic owner/member reference shape; requests without subjects use an unscoped reference instead. The requested task is last in the input, after schema and repair feedback. These changes reduce ambiguous generation instructions; they do not replace read-only, schema, anchor, native-path and EXPLAIN validation.

Other operations continue to use the extended `cypher`, `parameters`, `intent` contract, and extended path responses remain accepted for compatibility. Intent distinguishes direct neighbors, bounded paths, aggregation, filtering and lookup, along with ordered/intermediate-only flags, physical traversal direction, overall journey endpoints, requested sub-route endpoints, schema-backed anchors and individual question parts. Each part supplies actual schema evidence, result columns and support status. Generic English question hints independently catch common adjacency/path/aggregation and between-clause mismatches; other language/domain understanding remains the model's responsibility. Locally derived path metadata supports unambiguous simple node predicates and equality predicates over known labelled variables; complex forms remain subject to correction, not guessed interpretation.

Question-part IDs are opaque metadata strings, so numeric strings such as `"1"` are valid. IDs must be unique, nonempty and at most 64 characters, using letters, digits, underscores, dots or hyphens. This does not relax Cypher parameter-name rules. Repair feedback always restates the original sub-route, journey, subject and descriptive request in `requiredInterpretation`, including before any successful read, and includes previous part identities when available. The model is instructed to keep subject/membership matches outside the native path and to respect observed direction instead of closing a cycle through the subject node. These are generation instructions, not automatic semantic query rewrites or guarantees of model success.

Anchors use only actual schema labels/properties. Small parameterized database reads retrieve canonical-name candidates. A single unambiguous candidate may replace the corresponding parameter value (for example, a partial name with a stored canonical name); ambiguous candidates are given to the model, not arbitrarily selected. Unsupported anchor properties are rejected. Business meanings cannot be deduced reliably from labels alone; continue using `T2C_INSTRUCTIONS` or manual schema descriptions for group/route semantics.

Before strict validation, narrow candidate normalization removes well-formed unused parameter definitions (retaining journey/intent metadata), and may replace a direct `RETURN nodes(p) AS column` projection with the already-bound native `p` when that column is explicitly declared as path evidence. For an empty anchor list it derives both endpoint anchors only from unambiguous simple node identity predicates whose label/property exists in the schema and whose parameter values match the declared endpoints. Each change and the resulting candidate are logged. Complex expressions, unknown schema, missing/duplicate/malformed parameters and ambiguous anchors remain subject to rejection/correction; no labels, edges, business predicates, subject restrictions or graph facts are invented. All normalized queries still pass the same read-only, parameter, semantic and Neo4j EXPLAIN checks. This normalization does not establish that the model included every business/subject constraint.

For common English `from ... to ... on/using/via ...` journey phrasing, question hints separately retain the named subject/context. A supported path proposal with no corresponding parameter referenced before `RETURN` receives `SUBJECT_SCOPE_REQUIRED` feedback; unused journey metadata is not a subject predicate. The model must express the scope using actual schema or report it unsupported. This detects an omitted subject, not arbitrary incorrect joins or predicate placement; domain constraints still require model interpretation and appropriate schema guidance.

A simple candidate that returns an unbound path variable may be repaired only when one variable-length endpoint segment is identifiable from both declared endpoint anchors and directly precedes `RETURN`. The normalizer binds that segment and preserves any preceding subject/membership match outside it. Ambiguous segments and complex clauses remain model/EXPLAIN responsibilities. It never creates edges or changes traversal direction.

An unsupplied parameter in a direct `RETURN nodes($path) AS ...` projection is not a native path value. With identifiable endpoint anchors, normalization may replace that projection with a bound native query variable or the unique variable-length endpoint segment described above. A supplied scalar/list parameter is never promoted into native path evidence, and other missing parameters still fail validation. Original and normalized candidates are logged separately.

Failed anchor lookups are retained with empty candidate lists and proposals are resolved anew after each correction. For a named journey subject whose technical identity field (`id`, `key`, `uuid`, `identifier` or `code`) matches nothing, a simple one-property node predicate may be corrected to `name`, `title` or `label` only with an exact case-insensitive display-name match, one node identity across all matching display fields, and no duplicate exact candidates. All fields must exist in the actual schema and the subject must equal the named question context. Partial names, multiple identities and complex predicates do not justify field substitution. Corrections retain the original property and resolution method in `resolvedAnchors`, are logged, and are revalidated before Neo4j EXPLAIN. An unresolved subject is explicitly rejected rather than hidden by successful station resolution.

Ordered path questions must return a **native Neo4j path**, identified by `intent.pathColumn`. A JSON map or list of names does not constitute native path evidence. The driver adapter records native provenance separately from row data. The evaluator checks connectivity, requested direction and unambiguous endpoints, clips a full-journey path to the requested sub-route when present, and excludes endpoints for intermediate-only questions. Projection uses the native traversal sequence or a justified reversal, never alphabetic sorting. Selected nodes are returned as ordered rows with `position`, `entity`, `node`, `pathStart` and `pathEnd`. The verified path metadata retains its endpoints and ordered nodes.

Syntax, parameter, single-hop interpretation, endpoint, direction, missing-path, aggregation, filtering, empty-value, truncation and missing-component issues produce structured feedback. Repairs receive the prior query (capped at 4000 characters with an explicit truncation marker), parameters, resolved candidates, failed checks and earlier successful parts. There is one initial generation plus **at most two** repair generations; no automatic provider/database substitution or unbounded retry. Explicitly unsupported schema parts do not trigger blind attempts to manufacture data. Later failed reads never erase earlier useful evidence; complementary successful reads are merged with coverage/provenance and existing output caps.

`semanticStatus` is `COMPLETE`, `PARTIAL`, `EMPTY` or `UNVERIFIED` (legacy model output without explicit interpretation). `coverage` distinguishes answered and unanswered parts with reasons. Identity/name/order and structural endpoint fields (such as origin/destination) alone cannot substantiate qualitative suitability claims. A successful route with no descriptive schema support is returned as `PARTIAL` with that descriptive request explicitly unanswered. Zero/false aggregation values remain valid data. Completeness is not inferred merely from a nonempty row or successful `EXPLAIN`.

The railway regression fixture contains 31 stops. Its acceptance test first supplies an incorrect single-hop proposal, verifies structured correction, resolves the sub-route to **Lahore Jn. -> Multan Cantt**, and returns **Raiwind Jn., Pattoki, Okara, Sahiwal, Chichawatni, Mian Channu, Khanewal Jn** in native travel order. Family suitability is explicitly unanswered because that fixture has no supporting attributes. No train/station names, railway relationship names or railway-specific queries appear in production logic.

These checks are deliberately conservative and do not constitute a full Cypher theorem prover or universal natural-language interpreter. They establish native path order/connectivity and declared coverage, not arbitrary business truth, optimality, current-world facts or completeness of a sampled schema. Misleading schema guidance, ambiguous identities, concurrent graph changes and model errors can still require user clarification or further retrieval. Each read/repair uses its own bounded transaction, not one snapshot across all attempts.

CLI connection messages show the configured Neo4j server origin, database and username before connection, then report success only after a read against the selected database. Passwords, URI credentials, URI query parameters and routing context are excluded. A result summary shows returned/populated row counts and graph node/edge counts. `dataStatus` distinguishes `NO_ROWS`, `EMPTY_VALUES` (rows containing only null, blank or recursively empty values) and `DATA`; `populatedRowCount` counts rows with at least one value. Zero and false remain meaningful values. An empty aggregate row is retained but emits a warning: `collect()`/optional matches can produce one result row without matching records. `DATA` does not imply that every part of the question was answered. Verify the exported `NEO4J_DATABASE` as well as the Studio node setting when troubleshooting.

## Read boundary

Generated Cypher is limited to a read-query subset. The lexical validator rejects writes, procedures (including CALL subqueries/APOC), administrative statements, `LOAD CSV`, database switching, comments with unterminated delimiters, multiple statements, unmatched parameters and oversized output. Neo4j `EXPLAIN` must also classify the query `READ_ONLY` before the unchanged query executes with parameters in a READ-routed, timed transaction. Unsupported syntax requires another bounded model attempt or an explicit failure. There is no automatic fallback to another database, provider, KG retriever or unvalidated query.

`start` is permitted as a node variable and return alias; it is not treated as a write keyword. Legacy `START` statements remain outside the allowed query prefixes. Path feedback explicitly rejects constructions such as `nodes([a,b])`: endpoint lists are not paths. The generation prompt demonstrates generic native-path assignment/return syntax, while actual labels, types and predicates must come from the supplied schema. Missing endpoint anchors and excessive hop bounds are reported together so one correction can address all detected issues.

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
