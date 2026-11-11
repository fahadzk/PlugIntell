# Plugin developer guide

## Runtime contract

Each plugin is a Java 21 Maven module implementing `com.core.docintel.pipeline.api.Plugin`. Keep the public plugin API dependency version aligned with the root reactor. The runtime verifies the JAR manifest against the source catalog before using the implementation.

```java
public final class ExamplePlugin implements ConfigurablePlugin, PreflightPlugin, ManagedPlugin {
    private ExampleConfig config;

    @Override public PluginManifest manifest() { return ExampleManifest.VALUE; }
    @Override public void configure(Map<String, Object> values) { config = ExampleConfig.from(values); }
    @Override public void preflight(PreflightContext context) { /* validate dependencies */ }
    @Override public Object execute(Object input) { /* validate type and return output */ }
    @Override public void close() { /* release clients */ }
}
```

`ConfigurablePlugin` receives a fresh map for each pipeline node. Avoid global mutable state. `PreflightPlugin` can test local or remote prerequisites before execution; plugins that implement it are advertised with the `connection-preflight` capability and get a Studio **Test connection** action. `ManagedPlugin.close()` is called when the pipeline run finishes or preflight fails.

For a node that combines multiple incoming edges, implement `FanInPlugin.executeInputs(List<Object>)` and declare the `fan-in` manifest capability. Every incoming edge must still provide the node's declared input type. Inputs arrive in deterministic graph execution order. The fan-in node receives its inputs after the prerequisite branches complete; the fan-in contract does not imply thread safety or parallel execution.

For concurrent execution, implement the `ParallelSafePlugin` marker and declare the `parallel-safe` capability. The executor only runs a ready graph layer concurrently when every node in that layer declares and implements the marker. Each node gets its own plugin instance. Set `PIPELINE_EXECUTION_MAX_PARALLELISM` to a value from 1 to 64 (default 4) to cap nodes running at once per pipeline execution. Do not make this declaration unless the plugin and its external clients are safe for concurrent use.

## Manifest and configuration schema

Place `META-INF/PluginManifest.yaml` in `src/main/resources` and use a unique plugin ID and exact pinned version. The plugin manifest declares identity, input/output type names, capabilities, and configuration JSON Schema fields.

```yaml
plugin:
  id: example-parser
  name: Example parser
  version: 1.0.0
  apiVersion: "1.0"
  entrypoint: com.example.ExamplePlugin
  input: {type: File}
  output: {type: TextDocument}
  workflows: [DOCUMENT_INGESTION]
  capabilities: [document-parser, connection-preflight]
  description: Parses example files.
  configuration:
    required: [mode]
    properties:
      mode: {type: string, enum: [fast, accurate], default: accurate}
      endpoint: {type: string, format: uri, description: Provider URL.}
      apiKey: {type: string, format: secret-reference, sensitive: true}
```

Declare `plugin.workflows` as a nonempty list containing `DOCUMENT_INGESTION`, `QUERY_PROCESSING`, or both. This metadata controls Studio usage filtering and lane validation independently of port type compatibility. Older manifests without the field default to both workflows for compatibility. Invalid workflow names are rejected by catalog discovery.

Configuration values are validated from the manifest. Sensitive values must be `secret://env/NAME` references; the server resolves them from its environment at execution or connection-test time. Do not include secrets in defaults, exception messages, logs, or returned output. Connection-test responses redact resolved values.

Plugins declaring `connection-preflight` may be included in reusable Studio connection profiles. One named profile can hold settings for several plugins, with each provider configuration pinned to that plugin's version. A pipeline node can set the Studio-managed `connectionRef` field to a profile ID; the node receives the matching plugin's settings, then node-specific values override them. The synthetic reference field is removed before configuration reaches the plugin. Profiles remain editable while drafts reference them. Publishing copies merged settings into the immutable workflow snapshot and removes `connectionRef`; resolved secret values are never captured. Older reference-based versions receive a separate persistent snapshot on first access or before profile mutation. Deletion is blocked only by current draft references. Keep provider settings in the normal plugin schema so the same validation and secret-reference rules apply.

The Studio renders JSON Schema object properties, nested objects, enums, arrays, required markers, numeric bounds, defaults, and secret references. Conditional schemas, arbitrary UI widgets, and custom plugin-specific controls are not yet rendered. Array values use one item per line; object items use one JSON object per line.

## Build, discover, and test

String fields may declare `format: multiline` for prompt editors or `format: json` for bounded JSON-text policies. The catalog preserves manifest enums and scalar `allOf` `if`/`then` required-field conditions; backend complete-configuration validation checks these conditions, and JSON-text fields reject malformed/duplicate/trailing JSON. Studio renders the enum and text editors; it does not dynamically evaluate arbitrary conditional schemas. Semantic cross-field/plugin policy checks still run during configuration/preflight.

1. Add the plugin as a Maven module under `plugins/` and include it in the root `pom.xml`.
2. Build the plugin JAR with `mvn package` from the repository root.
3. Use **Reload** in the Studio or `POST /api/v1/plugins/reload` to refresh trusted local manifests.
4. Validate plugin configuration with `POST /api/v1/plugins/{id}/validate-config`.
5. For `PreflightPlugin` implementations, test external dependencies with `POST /api/v1/plugins/{id}/test-connection`.
6. Add deterministic contract tests for input validation, output type, configuration, failure behavior, preflight and cleanup.

Plugin JARs execute in-process. A classloader boundary is not a sandbox. Only install trusted plugin code; use a separate process/container boundary before loading untrusted plugins.

## Domain messages and progress

Plugins needing graph authorization scope can call `PluginMessages.executionContext()` through the protected `AbstractPlugin.executionContext()` helper. The built-in attempt reporter supplies run, node and published pipeline provenance. Scope comes from trusted host environment (`PLUGINTELL_GRAPH_NAMESPACE`, `PLUGINTELL_GRAPH_ACCESS_SCOPE`) with no permissive fallback. Plain/no-op reporters have no published provenance. Never derive this context from source text, model JSON, query filters or editable node configuration. These environment values define a fixed host partition, not per-user tenant isolation. See [the graph plugin guide](knowledge-graph.md).

Extend AbstractPlugin and keep both a no-argument constructor and a public PluginMessages constructor calling super(messages). The final inherited dependency defaults to PluginMessages.noop(). Use emit(code, payload), progress(completed, total, unit), info(message), and warning(message). Plugins needing another superclass can compose a final PluginMessages field instead. execute(Object) and existing configuration/preflight/cleanup contracts are unchanged.

Messages supply domain details only. The shared runtime supplies schema/event/run/node/plugin/attempt identity, per-run sequence, timestamps, lifecycle types and measured durations. Pass the same reporter explicitly to background work; never look up a global current run or assume ThreadLocal propagation. An attempt reporter expires at call exit/timeout, and later messages are dropped/counted. Reporting retries receive fresh plugin instances; keep required durable state in the provider, not the plugin object. Legacy plugins still receive automatic lifecycle events.

Declare `messages` in the manifest capabilities to select your public `PluginMessages` constructor in exported Java assembly. The catalog validates that constructor, including for plugins using composition. The Python utility uses the explicit `--reporting-plugins` list for custom classes; each must already be selected in a workflow.

Emit counts and safe stages, never documents, prompts, questions, vectors, credentials or raw provider responses. Use null totals for unknown progress and never invent token counts. Keep all Kafka/Micronaut/HTTP/UI dependencies out of processing modules. See [reporting and delivery guide](../operations/events-and-generated-apps.md) for adapters, isolation and constructor mappings used by generators.
