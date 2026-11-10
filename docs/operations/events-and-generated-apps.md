# Events, monitoring, and generated applications

## Build and selected capabilities

The existing Java 21/Maven/Micronaut/static JavaScript application is preserved. Build the default distribution with `mvn package -DskipTests`. Kafka is an optional **build capability**: `mvn -Pkafka clean package` includes the actual Apache Kafka producer. Its client dependency is excluded by default. Switching this profile requires a clean build.

The default catalog marks Kafka unavailable, connection testing reports failure, and Studio rejects Kafka generation until a producer-enabled distribution is built. No disabled adapter pretends to publish messages.

The JVM combinations are CLI only, CLI plus Kafka, web with UI, and web with UI plus Kafka. Standalone UI destinations require the Micronaut web host; Studio reuses its existing host. Native generation supports CLI and Micronaut web/UI projects. Studio native projects use `run.bat serve` / `sh run.sh serve`; Python-generated projects use `run-native.bat serve` / `sh run-native.sh serve` after `mvn -Pnative package`. The native executable embeds monitor/template resources, keeps the same HTTP/SSE/result protocol, and does not require Java or external JARs at runtime. Kafka native combinations remain rejected in Studio and omit the Python native profile. Provider reachability metadata and the native compiler toolchain remain necessary.

## Plugin reporting

`pipeline-api` has no HTTP, Micronaut, Kafka, or UI dependencies. `Plugin.execute(Object)` is unchanged.

~~~java
public final class MyPlugin extends AbstractPlugin {
    public MyPlugin() { }
    public MyPlugin(PluginMessages messages) { super(messages); }
    public Object execute(Object input) {
        super.emit("embedding.started", Map.of("chunkCount", 2));
        super.progress(1, 2, "chunks");
        super.info("Preparing next batch");
        super.warning("A source was unavailable");
        return input;
    }
    // manifest() remains required.
}
~~~

Plugins extending another class can keep a final `PluginMessages` field and call its same helpers. `PluginMessages.noop()` and `NoopPluginMessages.INSTANCE` discard reports. Unknown totals use `progress(completed, (Long) null, unit)`. Reports contain counts, never invented percentages.

Custom reporting plugins declare `messages` in their trusted manifest capabilities and expose a public `PluginMessages` constructor. The catalog verifies that contract; generated assembly calls it directly. The Python utility accepts the equivalent explicit `--reporting-plugins` class list, restricted to the already selected, validated plugin names. This also supports plugins using constructor composition rather than inheritance.

Migrated built-ins keep no-argument constructors and add reporting constructors. They report real parsing/page milestones, produced chunks, completed embeddings, confirmed stored counts, retrieval stages/counts, and generation stages. No token usage is inferred. Configuration, preflight, types, outputs, and cleanup are preserved.

Do not emit document text, prompts, questions, answers, vectors, credentials, or raw provider responses. Built-ins emit metadata only. The shared sanitizer limits payload depth/width and string size, redacts sensitive field names and known environment/resolved-secret values, and never renders HTML. Third-party plugins remain responsible for what they report.

## Runtime and attempt identity

`RunEvents` owns the version-1 envelope: UUID event ID, run ID, pipeline identity/version when known, node/plugin identity, attempt UUID/number, per-run sequence, timestamp, type/severity, code/message, payload, and optional measured duration. Plugins supply only domain messages and cannot set authoritative lifecycle types or identities.

Both standalone `Pipeline` and Studio `GraphExecutionManager`/`NodeTaskRunner` use this foundation. Wrappers report run/node/attempt start, success/failure, cancellation request, actual termination, and real handoff events. Studio's retry/deadline policies additionally report retries and timeouts. Studio's `PREFLIGHT_*` events identify preparation and include its preflight attempt count; standalone preparation failures become run failures without a separate preflight event stream. Published exports carry their pipeline ID/version; developer utility projects without a publication use null identity/version.

Reporting attempts have fixed reporters injected into constructors. Studio prepares an instance for attempt one and constructs a fresh reporting instance on retry, reapplies configuration, and repeats preflight. Legacy plugins without reporting constructors retain their existing retry instances and receive wrapper lifecycle events. Background tasks must capture/pass the reporter explicitly. No global mutable current run or assumed ThreadLocal propagation is used.

Reporters expire at call exit or timeout before terminal persistence. Later domain messages are discarded and counted as `lateEvents`. Termination confirmation retains its original attempt identity. Obsolete attempts cannot change current progress/state. Retry resets progress rather than forcing monotonic percentages.

Cancellation requested differs from confirmed stopping. Interrupt-ignoring calls may remain active after the wait deadline. Retries are suppressed without confirmed stopping, and cleanup is deferred until calls actually stop. Java cannot safely force arbitrary in-process plugin code to terminate.

Generated processing, adapter, and host assembly uses direct constructors and explicit mappings. There is no generated adapter `Class.forName`, reflective construction, `ServiceLoader`, or runtime DI selection. The existing JAR plugin loader remains a reflective compatibility boundary. The optional host uses Micronaut compile-time infrastructure.

## Destinations and delivery

Adapters are consumers outside processing nodes/edges and never affect ordering. Each adapter instance has an independent worker and bounded 256-event queue. Overlapping subscriptions are ORed: one event is delivered at most once to an instance.

Pending progress for one attempt is coalesced. A terminal event can evict an older nonterminal event. If every queued event is terminal, the new event is dropped. There is no durable outbox or lossless terminal guarantee. Construction, delivery, and health collection run on isolated workers. Failures do not become recursive processing events.

Health reports queued, delivered-to-adapter, dropped, coalesced, failed, last failure class, and cached adapter health. Kafka's nested delivered/failed counts describe broker delivery callbacks; dispatcher acceptance is not a broker acknowledgement. Each destination worker owns construction, delivery and adapter close/flush. Dispatchers wait for draining and close against a shared two-second shutdown budget, including a final health refresh. Pending events may be discarded; uninterruptible adapter code may outlive that budget on a daemon worker thread. Generated CLI applications print fixed-field delivery-failure/drop summaries to stderr without failing successful processing; Studio-generated applications also print healthy summaries with `--debug`. Arbitrary adapter health fields are not printed.

Studio retains its canonical runtime trace. Configured UI subscriptions project progress/domain details and timeline entries. Hidden envelopes retain sequence/event IDs with `visible: false`. Hidden domain payload/message/code are cleared, while hidden lifecycle events still maintain authoritative state. Filters therefore survive snapshot/reconnect without sequence gaps or revealing filtered counts.

## Studio and versioned settings

1. Choose **Adapters** and add an instance.
2. Attach it in a node inspector, drag it onto a node/lane, or use the workflow's **Events** action.
3. Configure filters/settings in **Monitoring** and use **Test adapter**.
4. Select **Command line** or **Micronaut web host**, plus a standard, water, or imported template.
5. Save and publish, then run and open **Live monitor**.

Optional `capabilities` contains `adapters`, `subscriptions`, `host`, and `template`. It persists in drafts and immutable publications with optimistic concurrency/import/export. Adapter versions are pinned to `"1"`. Subscriptions name an adapter, workflow, node IDs, event types, and severities. Empty node lists mean workflow-wide attachment. Unknown IDs/fields/pins, raw Kafka credentials, invalid hosts, and malformed filters are rejected at the backend. Removing a sole node subscription removes it rather than widening it.

Studio's host choice governs generated applications; it starts no additional Studio server. See [Studio workbench](studio-workbench.md) for controls and accessibility details.

## Protocol, replay, and retention

Existing polling routes and historical execution-event response fields remain available.

| Route | Purpose |
| --- | --- |
| GET /api/v1/adapters | Versioned catalog, schema, availability. |
| POST /api/v1/adapters/{type}/test | Connection check with a config object. |
| GET /api/v1/templates | Built-ins and immutable imported descriptors. |
| POST /api/v1/templates/import?trustedExecutable=false | Multipart file ZIP import. |
| GET /api/v1/executions/{id}/monitor | Descriptor, state, cursor, recent events, health, template. |
| GET /api/v1/executions/{id}/stream?afterSequence=N | SSE replay and live updates; Last-Event-ID supported. |

Descriptors provide initial nodes, labels/capabilities, and processing edges before any event arrives. Snapshots contain current attempts, real progress/unknown totals, measured durations, safe I/O type/count summaries, and termination confirmation.

SSE `execution` uses sequence as its SSE ID and retains the UUID in `eventId`. `gap` carries a canonical snapshot for expired/ahead cursors. `heartbeat` keeps idle streams active. Snapshot-then-cursor replay and discontinuity checks protect reconnect races. Clients deduplicate by run and sequence while preserving the event UUID. Cursor polling avoids unbounded subscriber queues; backpressure and cancellation clean up subscriptions. A global limit allows 128 active SSE subscribers. Studio fetch-SSE sends existing bearer headers. Browsers never connect to Kafka.

SQLite adds `runtime_snapshots` and `runtime_events` without replacing old rows. Structured replay retains 5,000 events per run; snapshots preserve current state beyond event eviction. The existing list returns 500 jobs, but total SQLite jobs/snapshots are not automatically expired. Operators must manage database retention/backups. Active jobs become failed after restart; they are not resumed. Pre-telemetry executions have a topology/status view without invented progress.

Studio's internal SQLite destination uses one daemon platform thread, a bounded 4,096-record queue and transactions of up to 128 records, with a two-second shutdown drain. Native SQLite calls and idle waits therefore do not occupy Java 21 virtual execution carriers. It never performs telemetry writes inside a plugin call. Its `_studio-history` health reports queue/delivery/drop/coalescing/failure counts. The live legacy polling view merges a bounded recent tail with committed history. Committed rows survive restart; a crash or an exhausted drain can lose pending records. This is best-effort buffered telemetry, not a durable outbox. Progress snapshots retain the latest values per severity/current attempt independently of replay eviction, so UI filters do not lose their selected counts when old events expire.

Standalone history is memory-only, at most 100 retained runs and 5,000 events per run; restart loses it. Generated hosts retain a separate projected query result with each retained run. `GET /api/v1/executions/{id}/result` returns `Cache-Control: no-store`, run identity/status and `available`; successful supported query outputs also contain `answer`, optional original `query`, and source/page/score citation metadata. Pending, failed, cancelled, ingestion and unsupported outputs return `available: false`. This endpoint belongs to the generated host, not Studio. Answers are limited to 262,144 characters, queries to 16,384, and citations to 100; `truncated`/`citationsTruncated` flag shortened answers/lists. Citation IDs and source names are bounded to 256/1,024 characters. Retrieved passages and vectors are never retained by this projection. These results are intentionally separate from snapshots, SSE, adapters, custom-template messages and structured logs. The browser retains 500 timeline rows and 25 attempts per node. These features do not provide checkpoints, provider token streaming, distributed execution, or exactly-once telemetry.

## Generate and run JVM applications

Studio's build page accepts a published project, empty absolute destination on the backend machine, and JAR. Each nonempty lane must remain linear. Studio runtime DAG capabilities remain available separately.

Generated files include explicit `PipelineAssembly`, workflow classes, a descriptor, selected libraries, startup scripts, configuration examples, and web assets only for the web host. Download/extract the project ZIP and keep `lib/` beside `target/`.

~~~shell
mvn package
java -jar target/myproject-1.0.0.jar --mode ingestion --input document.txt
java -jar target/myproject-1.0.0.jar --mode query --query "Your question"
~~~

Only web-enabled output supports:

~~~shell
java -jar target/myproject-1.0.0.jar serve
~~~

The host binds to loopback, prints its URL, optionally attempts browser launch, and stays up across runs. The shared page displays successful query answers as plain text with source/page citations; **Open monitor** in execution history restores the selected result. This supports `AnswerResult` and plain `String` outputs; other result types remain summary-only. It clears previous output on run switches and ignores delayed responses from older selections. The shared page supplies ingestion/query controls and history. Web ingestion files must be inside `PIPELINE_INPUT_ROOT` (default `data/ingestion`); supply relative paths. CLI ingestion retains readable explicit paths. Headless use needs no browser.

Generated `run.bat` and `run.sh` list their available commands when called without arguments. Web-enabled projects include `run.bat serve` / `sh run.sh serve` alongside their CLI workflows; CLI-only output omits that option. Scripts use the project directory, prefer `JAVA_HOME`, and preserve Java's exit status.

The generated Micronaut host writes startup and structured execution logs to its terminal and `logs/server-0.log`. Files rotate through three generations of 5 MB each. Set `PIPELINE_LOG_DIR` before startup to choose another directory. Logs identify runs, nodes, attempts, measured counts and failures; arbitrary payloads, documents, prompts, answers and vectors are not logged. Known secrets and control characters are redacted/sanitized. This observer uses the shared bounded dispatcher, dedicated daemon platform workers and JDK logging, so blocking logging I/O does not occupy Java 21 processing carriers. It observes the full run independently of UI/Kafka destination filters. A file-logging setup failure leaves console logging available.

From the generated project folder, follow the file in PowerShell:

~~~powershell
Get-Content .\logs\server-0.log -Wait -Tail 100
~~~

Plugins' direct stdout/stderr appears in the same server terminal. To capture that output too, create `logs` and run `run.bat serve > logs\console.log 2>&1` in Command Prompt. Generated READMEs include these instructions. Existing downloads must be regenerated to receive updated scripts and host libraries.

Generated hosts expose execution/monitoring controls, not Studio's pipeline editing, provider configuration, arbitrary filesystem, or plugin control APIs.

The Python utility supports the same selections:

~~~powershell
python tools/generate_native_launcher.py --plugins 'com.core.docintel.plugins.ExtractTextPlugin,com.core.docintel.plugins.StaticSplitterPlugin,com.core.docintel.plugins.TestEmbedderPlugin,com.core.docintel.plugins.TestWriterPlugin' --dependency 'com.core.docintel:extract-text-plugin:1.0.0' --dependency 'com.core.docintel:static-splitter-plugin:1.0.0' --dependency 'com.core.docintel:test-embedder-plugin:1.0.0' --dependency 'com.core.docintel:test-writer-plugin:1.0.0' --output './target/generated' --project-name Example --package generated.example --main-class Main
~~~

Add `--web --ui --template water`, Kafka flags below, or both. `--capabilities settings.json` supplies the shared settings model with explicit subscriptions. Utility node IDs are `node-1`, `node-2`, etc. per lane. Existing `ingest` and interactive `query` commands remain. Install matching reactor artifacts before building its coordinate-based Maven POM outside the reactor. See [GENERATOR.md](../../GENERATOR.md).

## Kafka and Confluent

Configuration fields: `bootstrapServers`, `topic`, `clientId`; `securityProtocol` (PLAINTEXT, SSL, SASL_SSL, SASL_PLAINTEXT); `saslMechanism` (PLAIN, SCRAM-SHA-256, SCRAM-SHA-512); `username`, `password`, `truststorePassword` as `secret://env/NAME`; optional `truststoreLocation`.

For Confluent-hosted Kafka, use its bootstrap address, SASL_SSL/PLAIN, and API key/secret environment references. Default JVM trust roots apply unless a truststore is supplied. Schema Registry, Avro, Kafka Streams, and Confluent REST proxy are not implemented.

Records are JSON schema version 1 with run-ID partition keys. Producer limits are 1 MB buffering, 1-second metadata/buffer blocking, 3-second requests, 5-second delivery, two retries, all acknowledgements, and one in-flight request. Idempotence reduces broker retry duplicates within a producer session, but replay/restart may duplicate records. Ordering follows the destination worker and broker partition; dropping/coalescing creates gaps. See [Apache Kafka producer configuration](https://kafka.apache.org/40/configuration/producer-configs/).

Python options include `--kafka-bootstrap-servers localhost:9092 --kafka-topic plugintell.events`, `--kafka-security-protocol SASL_SSL`, and username/password reference options.

Producer tests use Kafka MockProducer for serialization, keys, and callback failures. Broker integration requires `PLUGINTELL_KAFKA_TEST_BOOTSTRAP` and optionally topic/security/username/password test variables. It publishes a unique run and consumes the matching record. Run `mvn -Pkafka -pl pipeline-event-kafka -am test`. Without a broker that test is explicitly skipped, not passed.

## Templates and customization

See [Local UI templates](ui-templates.md) for the complete Studio workflow, Aurora Studio/Midnight Observatory bundles, external ZIP setup, typed settings, version pins, Python options and declarative hooks. Templates configure the shared Local UI view; adapter subscriptions/filters and generated host selection remain separate. Actual query answers and controls stay in the trusted host page outside the isolated renderer.

A version-1 ZIP includes manifest.json, index.html, styles.css, optional renderer.js, and local assets:

~~~json
{"schemaVersion":1,"id":"my-monitor","name":"My monitor","version":"1","html":"index.html","css":"styles.css","executable":false,"assets":[]}
~~~

Prefer declarative placeholders:

~~~html
<h1 data-pipeline-title></h1>
<p data-pipeline-status role="status"></p>
<div data-pipeline-nodes></div>
<ol data-pipeline-events></ol>
~~~

The host injects its trusted bridge and stylesheet. Imported IDs contain a SHA-256 suffix. Validation rejects traversal, duplicate/unsupported resources, undeclared execution, remote declarative content, and overruns: 2 MB compressed, 4 MB expanded, 100 files, 1 MB per resource.

Executable renderers declare `executable: true` and `renderer: "renderer.js"`, include the local script reference, and require explicit trust acknowledgement. They run in an iframe with scripts allowed and same-origin access disabled. Resource CSP/sandbox denies direct API/network connections. The parent supplies versioned `plugintell.snapshot` and `plugintell.event` messages without bearer tokens or Studio storage access. Treat plugin strings as text and honor event visibility. Ordering/reconnect/deduplication stay in the shared client.

Python accepts `--template-bundle my-monitor.zip`; executable bundles also require `--trusted-template`. Only web-enabled output bundles template assets. Standard/water share one state/client protocol. Water station activity follows lifecycle, and transfer animation follows actual HANDOFF events. Neither template fabricates queue levels, throughput, or streaming execution.

Executable templates receive `{schemaVersion: 1, type, data}` from their parent. `plugintell.snapshot` contains the descriptor, node states, optional measured progress, recent projected events, health and sequence cursor; `plugintell.event` contains one projected event envelope. The parent sends an updated snapshot after each event. Render snapshots as authoritative state; use event messages for optional activity/transfer effects without counting the same event twice. Verify `event.source === parent` and the schema version in custom message handlers. The declarative bridge consumes snapshots automatically.

See [verification results](events-verification.md) for executed commands, end-to-end checks and unavailable integrations.

