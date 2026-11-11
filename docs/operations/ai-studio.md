# AI Studio: recommendation, editable workflows and builds

Open `/ui/aistudio.html` from the **AI Studio** link in Studio. Enter a RAG description and click **Suggest plugins**. A complete recommendation can be saved as an editable two-lane project, or published and built using the existing Studio services.

## Network-hosted Laya

The [Laya Ollama documentation](https://ollama.com/library/laya) requires Ollama 0.40.0 or later and the `/v1/systemone` API. Pulling the model alone does not verify that endpoint is supported by your running server.

1. On the model machine, ensure `laya` is pulled and Ollama exposes its HTTP listener to your backend machine (for example configure `OLLAMA_HOST=0.0.0.0:11434` for that service and restart it). Allow the backend machine through the host firewall.
2. From the PlugIntell machine, verify the actual decision endpoint in PowerShell, replacing `192.168.1.50`:

```powershell
$body = @{model='laya';state='PDF document';questions=@{format=@{type='choice';instructions='Choose the document format';criteria=@{pdf='PDF document';text='Plain text document'}}}} | ConvertTo-Json -Depth 8
Invoke-RestMethod -Uri 'http://192.168.1.50:11434/v1/systemone' -Method Post -ContentType 'application/json' -Body $body
```

The response must include `answers.format.choice`. A 404 means the running server does not support this API; update to a compatible Ollama/laya-serve deployment.

3. In the shell that starts the backend, configure and build:

```powershell
$env:LAYA_BASE_URL='http://192.168.1.50:11434'
$env:LAYA_TIMEOUT_SECONDS='120'
# Optional, only if the remote service requires a bearer token:
# $env:LAYA_API_KEY='<server-side token>'
mvn -pl pipeline-builder-api -am package -DskipTests
.\run-backend.bat
```

For Windows Command Prompt, set the value with quotes around the whole assignment (not just the URL):

```bat
set "LAYA_BASE_URL=http://192.168.1.50:11434"
run-backend.bat
```

Restart the backend after changing the environment variable. The selector trims whitespace and one matching pair of surrounding quotes, so accidentally quoted environment values are accepted.

4. Open `http://127.0.0.1:8080/ui/aistudio.html`. Try `Extract PDFs, chunk and embed with Ollama, store in Neo4j and answer questions.` Expect four ingestion steps and two query steps, with actual catalog display names. Then try `Extract PDFs and use ChromaDB with Ollama for RAG.` Expect incomplete lanes with missing ChromaDB vector write and retrieval in their chain positions.
5. Stop the backend, set `LAYA_BASE_URL` to an unused local port, restart and retry the first prompt. Expect an actionable connection error and an enabled retry button.

Authentication uses the existing session token and sign-in flow. Existing backend role rules apply (ADMIN/EDITOR can POST recommendations). Credentials stay on the backend. `POST /api/v1/aistudio/recommend` takes `{"prompt":"original text"}` and returns ingestion/query objects with `complete` and ordered `steps`.

## Selection rules and current limits

The endpoint passes a snapshot from `PluginCatalog` directly into the `com.core.docintel.pipeline.builder.helpers.LayaPluginSelector` helper; no other component reads manifests. Java defines the six linear roles using exact logical ports and workflow eligibility. `tika-document-parser` (File -> TextDocument) and `langchain4j-splitter` (TextDocument -> ChunkCollection) are separate selectable plugins. The older combined `document-splitter` remains available for existing workflows but is not eligible for these separate AI Studio roles. Writer/retriever pairs require a shared catalog ID prefix (for example `neo4j-writer` / `neo4j-retrieval`); unknown naming conventions are conservatively excluded. This checks provider identity, not live connectivity, index settings or credentials. Set those through the selected connection profile or the plugin settings below the recommendation.

Explicit provider recognition currently covers Neo4j, ChromaDB/Chroma, Pinecone, Qdrant, Weaviate, Milvus, FAISS, pgvector, Elasticsearch, Ollama, OpenAI, Anthropic, Gemini, Cohere, Bedrock and Azure. Mentions are treated as requirements; use one provider per role and avoid negated provider mentions. Extend this Java vocabulary when adding providers. Unknown providers and nuanced constraints are not yet extracted. Missing roles are displayed in place and mark their lane incomplete.

Explicit catalog IDs and display names are constraints, applied before Laya selection; `Tika` and `LangChain4j` are also recognized when those technologies appear in a candidate's catalog metadata. Requesting multiple plugins for the same role produces a clear conflict error. Use positive plugin requests rather than negated mentions. For example, `Use Tika Document Parser and LangChain4j Splitter with Ollama and Neo4j` recommends those exact four ingestion plugins and the two query plugins. `Use PDF OCR to Text and Static Character Splitter with Ollama and Neo4j` selects a different ingestion chain. If every step has one eligible plugin, no Laya call is needed. Changing only the project name or top-K value need not change plugins; a quoted project name and a `top N` retrieval count are prefilled for review. Other natural-language settings are not extracted automatically.

Laya receives the unmodified prompt as `state` with typed `choice` questions mapping eligible installed IDs to catalog descriptions. Choices are sequential so previously selected ports/storage constrain later options. The documented API supports 2-26 choices: a sole eligible plugin is chosen deterministically in Java; more than 26 alternatives produces a clear error. Unknown or ineligible returned IDs fail the whole recommendation and allow retry. The selector is a backend helper, so it is not part of the plugin catalog. No fallback hides remote errors.

## Automated verification

```powershell
mvn -pl pipeline-builder-api -am test
node --check pipeline-builder-ui/aistudio.js
node --test pipeline-builder-ui/aistudio.test.cjs
```

`AiStudioTest` uses a local HTTP stub and the actual installed catalog for matching and unavailable-provider prompts, verifies original prompt/typed choices, chain order, storage pairing, invalid IDs and unreachable-service messages. No network Laya is required.

## Separate parser and splitter

Build the new modules with `mvn -pl plugins/tika-document-parser,plugins/langchain4j-splitter -am package`. Restart Studio (or reload its catalog) after installing them. Tika parses formats supported by Apache Tika into one logical text page; it does not provide OCR or physical page boundaries. Choose PDF OCR to Text for scanned PDFs or physical page citations. The LangChain4j splitter retains whatever document/source/page provenance its input supplies. Its manifest exposes `PLUGIN_LANGCHAIN4J_CHUNK_SIZE` (default 1000) and `PLUGIN_LANGCHAIN4J_CHUNK_OVERLAP` (default 100); overlap must be smaller than size.

## Create a workflow or build an application

1. In main Studio, open **Connections**, create or edit a multi-plugin connection profile, then click **Make default** next to it. There is at most one explicit default. Marking another profile replaces the previous default atomically; deleting it leaves no default. Existing databases migrate without selecting a profile automatically. This selection only affects new AI Studio projects; existing node references and published settings are unchanged.
2. Ask AI Studio for a complete recommendation. Both lanes must be complete. AI Studio selects the marked default, with an option to choose another profile or use node settings. Only entries matching a selected plugin ID and version are reused; the page reports how many plugins the profile covers. No provider is inferred from unrelated profile settings.
3. Review **Project name**, **Connection profile**, and **Plugin settings and overrides**. Fields use catalog schema defaults and the selected profile. Enter missing model names or settings. Secret fields accept only `secret://env/VARIABLE_NAME`; actual credentials are never put into drafts. Changing profiles resets the displayed overrides to that profile's values. A prompt containing a quoted project name prefills it; `top 5` prefills the retrieval limit. Changing the prompt requires a new recommendation.
4. Click **Create workflow in Studio** to save an editable schema-version-2 RAG project with ingestion/query nodes connected in recommendation order. Missing required configuration can be completed later. The page opens `/ui/studio.html?pipelineId=<id>` with the new project selected; it also appears in **Saved project**. This button does not publish or build.
5. To build instead, fill the absolute backend-machine output folder and choose JAR or native executable in **Build your application**, then click **Create project and build**. The page validates complete configuration, saves the draft, publishes a snapshot and starts the existing Maven build. It opens the existing progress/download page for that exact build ID. Native builds still require the configured GraalVM toolchain. Required settings must be present, but provider connectivity is not tested by this action.

A selected profile is stored as `connectionRef` on each covered node. Overrides stay on the node, and publishing captures the merged settings using the existing control service. The default-profile flag is not a live reference to all future projects. If a publish/build step fails after saving, **Open saved workflow in Studio** remains available; retrying on the same recommendation updates/reuses that draft instead of creating a second project. Refreshing AI Studio discards this client-side retry state. Build failures and logs remain available in the normal build page.

The browser reuses `/api/v1/pipelines`, validation, publish, and `/api/v1/projects/builds`; it adds no alternate execution or persistence path. Existing auth/RBAC and build destination validation apply. `POST /api/v1/connections/{id}/default` takes `{"expectedRevision":1}` and returns the updated profile, including `defaultProfile`. It requires ADMIN/EDITOR when authentication is enabled; stale revisions return 409 and missing IDs return 404.

## AI Studio page layout and shortcuts

The page guides users through Describe, Review plugins, and Create project. Example prompts fill the textbox for editing; they never submit automatically. Ctrl/Cmd+Enter submits the description. The two numbered plugin lanes show each plugin's role and whether the chain is complete. Missing plugins prevent creation actions until the recommendation is complete.

The project section displays profile coverage and a live count of missing required plugin settings. Required settings open automatically when missing, and validation focuses the relevant field. The editable-workflow and application-build actions have separate descriptions; the build output folder and format are visible in the build card. The layout stacks on narrow screens and uses the shared theme and density preferences. Loading states are announced, and reduced-motion preferences disable the loading animation.
