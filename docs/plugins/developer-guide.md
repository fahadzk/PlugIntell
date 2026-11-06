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
  capabilities: [document-parser, connection-preflight]
  description: Parses example files.
  configuration:
    required: [mode]
    properties:
      mode: {type: string, enum: [fast, accurate], default: accurate}
      endpoint: {type: string, format: uri, description: Provider URL.}
      apiKey: {type: string, format: secret-reference, sensitive: true}
```

Configuration values are validated from the manifest. Sensitive values must be `secret://env/NAME` references; the server resolves them from its environment at execution or connection-test time. Do not include secrets in defaults, exception messages, logs, or returned output. Connection-test responses redact resolved values.

Plugins declaring `connection-preflight` may be included in reusable Studio connection profiles. One named profile can hold settings for several plugins, with each provider configuration pinned to that plugin's version. A pipeline node can set the Studio-managed `connectionRef` field to a profile ID; the node receives the matching plugin's settings, then node-specific values override them. The synthetic reference field is removed before configuration reaches the plugin. Referenced profiles cannot be edited or deleted while a draft or published version uses them. Keep provider settings in the normal plugin schema so the same validation and secret-reference rules apply.

The Studio renders JSON Schema object properties, nested objects, enums, arrays, required markers, numeric bounds, defaults, and secret references. Conditional schemas, arbitrary UI widgets, and custom plugin-specific controls are not yet rendered. Array values use one item per line; object items use one JSON object per line.

## Build, discover, and test

1. Add the plugin as a Maven module under `plugins/` and include it in the root `pom.xml`.
2. Build the plugin JAR with `mvn package` from the repository root.
3. Use **Reload** in the Studio or `POST /api/v1/plugins/reload` to refresh trusted local manifests.
4. Validate plugin configuration with `POST /api/v1/plugins/{id}/validate-config`.
5. For `PreflightPlugin` implementations, test external dependencies with `POST /api/v1/plugins/{id}/test-connection`.
6. Add deterministic contract tests for input validation, output type, configuration, failure behavior, preflight and cleanup.

Plugin JARs execute in-process. A classloader boundary is not a sandbox. Only install trusted plugin code; use a separate process/container boundary before loading untrusted plugins.
