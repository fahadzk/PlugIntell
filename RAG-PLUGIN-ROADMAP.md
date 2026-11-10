# RAG Plugin Roadmap

This document proposes a plugin catalog and roadmap for PlugIntell RAG Pipeline Studio. It describes future capabilities, not a verified inventory of missing plugins. Repository inspection was unavailable when this proposal was written; reconcile it with existing source and documentation before implementation.

## Workflow overview

Organize the capabilities around three workflows: ingestion, answering, and evaluation.

```text
INGESTION
Source → Parse → Clean → Chunk → Embed → Chroma upsert

ANSWERING
Question → Input guard → Query embedding → Chroma retrieval
         → Rerank → Assemble context → Ollama answer → Output checks

EVALUATION
Saved dataset → Execute published answering version → Score → Compare runs
```

The baseline answering flow above omits optional query rewriting, hybrid retrieval, and evidence sufficiency checks for clarity.

## Core plugin catalog

Each row represents a capability; it does not necessarily require a separate Maven module. Reuse existing plugins and services where they already provide the behavior.

| Stage | Proposed plugin | Responsibility | Priority |
|---|---|---|---|
| Ingestion | Source connector | Load local files, URLs, or connected repositories; retain source identity | Essential |
| Ingestion | Document parser | Extract text, headings, tables, and page references | Essential |
| Ingestion | Text cleaner | Remove repeated headers, normalize whitespace, handle empty content | Essential |
| Chunking | Structure/token chunker | Split by headings, paragraphs, and token limits; establish a baseline | Essential |
| Chunking | Semantic chunker | Detect topic boundaries using similarity between sentence or paragraph embeddings | Next |
| Chunking | LLM chunker | Ask an LLM to identify meaningful boundaries and optional section labels | Next |
| Enrichment | Metadata extractor | Attach document title, section, page, language, tags, and access scope | Basic metadata essential |
| Embedding | Ollama embedder | Embed document chunks and user queries | Essential |
| Storage | Chroma writer | Batch upsert chunks, embeddings, IDs, and metadata | Essential |
| Retrieval | Chroma retriever | Retrieve candidates with configurable limits and metadata filters | Essential |
| Retrieval | Reranker | Reorder retrieved candidates against the question | High priority |
| Generation | Context assembler | Deduplicate results, enforce token budgets, and assign citation IDs | Essential |
| Generation | Ollama answer generator | Generate answers from supplied evidence, with citations and abstention instructions | Essential |
| Guardrails | Input/context guard | Apply configurable privacy rules and flag suspicious retrieved instructions | Essential baseline |
| Guardrails | Output validator | Check output structure, citation references, sensitive content, and grounding signals | Essential baseline |
| Evaluation | RAG evaluator | Measure retrieval and answer quality across a saved dataset | Essential for comparisons |

Ollama provides separate [embedding](https://docs.ollama.com/api/embed) and [chat](https://docs.ollama.com/api/chat) endpoints. Expose these as separate pipeline capabilities backed by a shared Ollama client. Chroma writing and retrieval should likewise share a client; its [query API](https://docs.trychroma.com/docs/querying-collections/query-and-get) accepts explicit query embeddings.

## Chunking strategies

Keep three strategies selectable and compare them on the same documents and questions.

| Strategy | How it chooses boundaries | Main tradeoff |
|---|---|---|
| Structure/token | Headings, paragraphs, token count | Predictable baseline; may split a topic |
| Semantic | Changes in embedding similarity | Requires threshold tuning and embedding work |
| LLM | Model identifies sections or topic transitions | Adds inference latency and requires validation |

For LLM chunking, have the model return boundary IDs over numbered source paragraphs, then assemble chunks from the original text. This preserves source wording and makes coverage, ordering, and missing paragraphs checkable. Handle oversized paragraphs with a deterministic splitter. Treat generated summaries as separate metadata.

All strategies should preserve source offsets, enforce maximum size, and provide a deterministic fallback.

## Additional capabilities

| Addition | Purpose |
|---|---|
| Document synchronization | Detect changed/deleted documents, skip unchanged content, and remove obsolete chunks |
| Hybrid retrieval and rank fusion | Combine semantic retrieval with a lexical retriever for names, codes, and exact terms |
| Query rewriting | Turn conversational follow-ups into standalone retrieval questions |
| Parent/neighbor expansion | Retrieve focused chunks, then include surrounding source context |
| Evidence sufficiency gate | Decide whether to answer, ask for clarification, or abstain |
| Context compression | Fit relevant evidence into the answer model's context budget |
| Metadata enrichment | Generate searchable titles, keywords, and summaries while retaining original content |
| Evaluation dataset generator | Draft test questions and expected evidence for human review |
| Feedback capture | Save user ratings and corrections for later evaluation |

Prioritize reranking and reliable citations before investing heavily in elaborate retrieval strategies, and validate their benefit through evaluation.

## Evaluation workflow

The evaluator should consume the question, retrieved chunks, answer, and optional reference answer or relevant source IDs.

Measure:

- **Retrieval:** recall@k, precision@k, MRR, or nDCG where relevance labels exist.
- **Answer:** correctness, relevance, faithfulness to retrieved evidence, citation accuracy, and appropriate abstention.
- **Operations:** latency, token usage, failures, and resource usage.

Ragas documents relevant concepts such as [context precision, context recall, response relevance, and faithfulness](https://docs.ragas.io/en/stable/concepts/metrics/available_metrics/). Implement selected metrics through the existing Java SPI and consider an external evaluator adapter later. Keep deterministic metrics separate from LLM judge scores, and record the judge model and rubric.

## Architecture considerations

Fit the implementation to the existing Java 21, Maven, Micronaut, and static HTML/CSS/JavaScript application. Preserve the existing plugin SPI and reuse application services across REST, MCP, UI, and CLI. Avoid new frameworks, modules, or dependencies unless the existing architecture cannot meet the requirement.

- Keep manifest configuration schemas authoritative for backend validation and Studio forms.
- Keep drafts editable and published pipeline versions immutable; bind executions to a specific published version.
- Track embedding model identity, revision, dimensions, and preprocessing. Matching dimensions alone does not make two embedding spaces compatible.
- Store stable document/chunk IDs, source references, and ingestion provenance.
- Bind evaluations to a published pipeline version and a corpus/index snapshot. An immutable pipeline can produce different results against changing data.
- Enforce access filters in backend retrieval services using authenticated identity.
- Validate filesystem paths, plugin IDs, graph structure, and user input at backend boundaries.
- Keep authorization, secret resolution, retries, and execution tracing in shared services. Persist and return secret references, never resolved secret values.
- Treat plugin JARs as trusted in-process code. A class loader is not a sandbox.
- Treat guardrail plugins as configurable checks; they cannot guarantee prompt-injection prevention or factual correctness.
- Keep SQLite metadata persistence distinct from Chroma vector storage, with an explicit synchronization and recovery approach.

## Suggested delivery sequence

1. **Baseline local RAG:** reuse or implement parsing, deterministic chunking, Ollama embeddings, Chroma writing/retrieval, context assembly, Ollama answers, citations, and baseline guardrails.
2. **Repeatable evaluation:** add a saved dataset, retrieval and answer metrics, operational measurements, and version/snapshot provenance.
3. **Quality experiments:** compare semantic chunking, LLM chunking, and reranking against the baseline.
4. **Retrieval and lifecycle improvements:** add document synchronization, hybrid retrieval, query rewriting, evidence sufficiency, and context expansion where evaluations show value.
5. **Studio comparison view:** show chunk boundaries, retrieved evidence, answers, quality scores, and latency side by side.

Before implementation, read [README.md](README.md), [ARCHITECTURE.md](ARCHITECTURE.md), and the relevant guides under `docs/`. Verify existing capabilities and contracts against source. Each delivered feature must compile, pass relevant tests, and document its actual scope and limitations.
