package com.core.docintel.text2cypher;

import com.core.docintel.pipeline.api.*;
import com.core.docintel.neo4j.*;
import com.core.docintel.llm.ExtractionModelClient;
import com.fasterxml.jackson.databind.JsonNode;
import org.neo4j.driver.exceptions.Neo4jException;

import java.util.*;

/**
 * Natural-language retrieval from an existing graph; returns facts, not an invented prose answer.
 */
public final class Neo4JText2CypherRetrievalPlugin extends AbstractPlugin implements ConfigurablePlugin, PreflightPlugin, ManagedPlugin {
    interface Model extends AutoCloseable {
        void verify();

        JsonNode generate(String system, String data);

        void close();
    }

    private GraphAccess graph;
    private Model model;
    private GraphSettings settings;
    private Map<String, Object> config;
    private final GraphAccess suppliedGraph;
    private final Model suppliedModel;
    private Map<String, Object> cachedSchema;
    private long schemaLoaded;

    public Neo4JText2CypherRetrievalPlugin() {
        this(PluginMessages.noop());
    }

    public Neo4JText2CypherRetrievalPlugin(PluginMessages messages) {
        this(messages, null, null);
    }

    Neo4JText2CypherRetrievalPlugin(PluginMessages messages, GraphAccess graph, Model model) {
        super(messages);
        suppliedGraph = graph;
        suppliedModel = model;
    }

    public PluginManifest manifest() {
        return new PluginManifest("neo4j-text2cypher-retrieval", "Neo4j Text-to-Cypher Retrieval", "1.0.0", "1.0", getClass().getName(), "SearchRequest", "RetrievalResult");
    }

    public void configure(Map<String, Object> values) {
        close();
        var merged = new LinkedHashMap<>(values);
        for (String key : List.of("LLM_PROVIDER", "NEO4J_URI", "NEO4J_USERNAME", "NEO4J_PASSWORD", "NEO4J_DATABASE", "OLLAMA_BASE_URL", "OLLAMA_CHAT_MODEL", "OLLAMA_TIMEOUT_SECONDS", "OPENAI_BASE_URL", "OPENAI_MODEL", "OPENAI_API_KEY", "OPENAI_TIMEOUT_SECONDS", "GEMINI_BASE_URL", "GEMINI_MODEL", "GEMINI_API_KEY", "GEMINI_TIMEOUT_SECONDS", "T2C_SCHEMA_JSON", "T2C_INSTRUCTIONS", "T2C_MAX_ROWS", "T2C_MAX_PATH_HOPS", "KG_REPAIR_RETRIES", "T2C_MAX_RESULT_BYTES", "T2C_SCHEMA_LIMIT", "T2C_SCHEMA_TTL_SECONDS", "T2C_LOG_CYPHER"))
            if (!merged.containsKey(key) && System.getenv(key) != null) merged.put(key, System.getenv(key));
        merged.putIfAbsent("KG_REPAIR_RETRIES", 2);
        config = Map.copyOf(merged);
        settings = ExtractionModelClient.settings(config);
        if (settings.neo4j.password().startsWith("secret://"))
            throw new IllegalArgumentException("NEO4J_PASSWORD must be resolved by the host.");
        number("T2C_MAX_ROWS", 100, 1, 1000);
        number("T2C_MAX_PATH_HOPS", 64, 1, 128);
        number("T2C_MAX_RESULT_BYTES", 262144, 4096, 2097152);
        number("T2C_SCHEMA_LIMIT", 256, 1, 2000);
        number("T2C_SCHEMA_TTL_SECONDS", 300, 0, 3600);
        String logging = text("T2C_LOG_CYPHER", "");
        if (!logging.isEmpty() && !Set.of("true", "false").contains(logging))
            throw new IllegalArgumentException("T2C_LOG_CYPHER must be true or false.");
        if (text("T2C_INSTRUCTIONS", "").length() > 8000)
            throw new IllegalArgumentException("T2C_INSTRUCTIONS exceeds 8000 characters.");
        if (!text("T2C_SCHEMA_JSON", "").isBlank() && !GraphPolicy.parse(text("T2C_SCHEMA_JSON", "")).isObject())
            throw new IllegalArgumentException("T2C_SCHEMA_JSON must be a JSON object.");
        try {
            if (suppliedModel != null) model = suppliedModel;
            else {
                var client = new ExtractionModelClient(config, settings);
                model = new Model() {
                    public void verify() {
                        client.verifyContextCapacity(settings.contextTokens);
                    }

                    public JsonNode generate(String system, String data) {
                        if(SemanticPlan.hints(GraphPolicy.parse(data).path("question").asText()).path()){
                            String focused=PathQueryContract.focusedData(data);settings.budget(system,focused);
                            info("Path generation uses focused sub-route task and schema-derived directed patterns; full question retained for result coverage.");
                            return client.request(system,focused,CypherGuard.schema(),settings.temperature,settings.contextTokens,settings.outputTokens,settings.responseBytes);
                        }
                        return client.request(system, data, SemanticPlan.schema(), settings.temperature, settings.contextTokens, settings.outputTokens, settings.responseBytes);
                    }

                    public void close() {
                        client.close();
                    }
                };
            }
            graph = suppliedGraph != null ? suppliedGraph : new Neo4jReadGraph(settings.neo4j, settings.queryTimeout);
        } catch (RuntimeException failure) {
            close();
            throw failure;
        }
        info("Neo4j Text-to-Cypher Retrieval initialized | provider: " + ExtractionModelClient.provider(config) + " | model: " + settings.model + ".");
    }

    private int number(String name, int fallback, int min, int max) {
        return GraphPolicy.number(config, name, fallback, min, max);
    }

    private String text(String name, String fallback) {
        return GraphPolicy.text(config, name, fallback);
    }

    private void ready() {
        if (graph == null || model == null)
            throw new IllegalStateException("Configure Neo4j Text-to-Cypher Retrieval before execution.");
    }

    private synchronized Map<String, Object> schema() {
        if (!text("T2C_SCHEMA_JSON", "").isBlank())
            return GraphPolicy.JSON.convertValue(GraphPolicy.parse(text("T2C_SCHEMA_JSON", "")), Map.class);
        if (cachedSchema == null || System.nanoTime() - schemaLoaded >= number("T2C_SCHEMA_TTL_SECONDS", 300, 0, 3600) * 1_000_000_000L) {
            var discovered = graph.schema(number("T2C_SCHEMA_LIMIT", 256, 1, 2000));
            if (GraphPolicy.encode(discovered).length() > 32000)
                throw new IllegalStateException("Discovered schema exceeds 32000 characters; provide a focused T2C_SCHEMA_JSON.");
            cachedSchema = discovered;
            schemaLoaded = System.nanoTime();
            emit("text2cypher.schema.loaded", Map.of("source", "database", "sampledPatterns", true));
        }
        return cachedSchema;
    }

    private String connectionTarget() {
        var uri = java.net.URI.create(settings.neo4j.uri());
        String server;
        try {
            server = new java.net.URI(uri.getScheme(), null, uri.getHost(), uri.getPort(), null, null, null).toString();
        } catch (java.net.URISyntaxException invalid) {
            throw new IllegalArgumentException("Invalid Neo4j server address.");
        }
        return "server: " + GraphPolicy.encode(server) + " | database: " + GraphPolicy.encode(settings.neo4j.database()) + " | username: " + GraphPolicy.encode(settings.neo4j.username());
    }

    public void preflight() {
        ready();
        try {
            info("Connecting to Neo4j | " + connectionTarget());
            graph.verify();
            info("Connected to Neo4j | " + connectionTarget());
            schema();
            model.verify();
        } catch (Neo4jException failure) {
            throw databaseFailure(failure);
        }
    }

    public Object execute(Object input) {
        ready();
        if (!(input instanceof SearchRequest request) || request.query().length() > 4000)
            throw new IllegalArgumentException("Text-to-Cypher expects a SearchRequest of at most 4000 characters.");
        long started = System.nanoTime();
        var audit = new RetrievalAudit(config);
        List<String> warnings = new ArrayList<>();
        int maxRows = number("T2C_MAX_ROWS", 100, 1, 1000);
        try {
            model.verify();
            var engine = new SemanticRetrieval(graph, model::generate, settings::budget, this::info, this::warning, this::emit, audit);
            var completed = engine.retrieve(request.query(), schema(), text("T2C_INSTRUCTIONS", ""), Math.min(2, settings.retries),
                    number("T2C_MAX_PATH_HOPS", 64, 1, 128), maxRows, number("T2C_MAX_RESULT_BYTES", 262144, 4096, 2097152), text("T2C_LOG_CYPHER", "true").equals("true"));
            var evaluation = completed.evaluation();
            var result = evaluation.result();
            var plan = completed.draft().query();
            long populatedRows = result.rows().stream().filter(SemanticEvaluation::hasValues).count();
            String dataStatus = result.rows().isEmpty() ? "NO_ROWS" : populatedRows == 0 ? "EMPTY_VALUES" : "DATA";
            String status = evaluation.status();
            info("Neo4j result | database: " + settings.neo4j.database() + " | rows: " + result.rows().size() + " | rows with values: " + populatedRows + " | verified paths: " + evaluation.paths().size() + " | status: " + status + ".");
            for (var issue : evaluation.issues()) warnings.add(issue.code() + ": " + issue.explanation());
            var unanswered = evaluation.coverage().stream().filter(part -> !part.get("status").equals("ANSWERED")).toList();
            for (var part : unanswered) warnings.add("Unanswered: " + part.get("request") + " — " + part.get("reason"));
            if (completed.exhausted()) warnings.add("The correction budget was exhausted or a later attempt failed; the best available successful partial results were retained.");
            if (populatedRows == 0 && !result.rows().isEmpty()) warnings.add("Neo4j returned " + result.rows().size() + " row(s), but every value is null, blank or an empty collection. An aggregate row does not prove graph matches.");
            if (result.truncated()) warnings.add("Results are truncated; completeness is not established.");
            warnings.add("Sources are database query rows, not verified original-document citations. Graph access follows the configured Neo4j account's permissions; no PlugIntell namespace filter is added.");
            for (String message : warnings) warning(audit.text(message));
            List<RetrievedChunk> chunks = new ArrayList<>();
            int ordinal = 0;
            for (var row : result.rows()) {
                Map<String,Object> payload = new LinkedHashMap<>();payload.put("data", row);
                if (ordinal == 0) {payload.put("retrievalStatus", status);payload.put("unansweredParts", unanswered);}
                String json = GraphPolicy.encode(payload);
                chunks.add(new RetrievedChunk(GraphPolicy.hash(settings.neo4j.database() + "\n" + plan.cypher() + "\n" + ordinal++ + "\n" + json), json, "Neo4j database " + settings.neo4j.database(), 1, 1.0));
            }
            List<Map<String,Object>> blocks = new ArrayList<>();
            if (!result.rows().isEmpty()) blocks.add(Map.of("type", "table", "data", result.rows()));
            if (!result.nodes().isEmpty()) blocks.add(Map.of("type", "graph", "data", Map.of("nodes", result.nodes(), "edges", result.edges())));
            Map<String,Object> output = new LinkedHashMap<>();
            output.put("strategy", "TEXT_TO_CYPHER");output.put("provider", ExtractionModelClient.provider(config));output.put("model", settings.model);output.put("database", settings.neo4j.database());
            output.put("dataStatus", dataStatus);output.put("semanticStatus", status);output.put("populatedRowCount", populatedRows);
            output.put("rows", result.rows());output.put("nodes", result.nodes());output.put("edges", result.edges());output.put("paths", evaluation.paths());
            output.put("intent", completed.draft().intent());output.put("coverage", evaluation.coverage());output.put("unansweredParts", unanswered);
            output.put("attempts", completed.attempts());output.put("resolvedAnchors", completed.resolutions());output.put("correctionBudgetExhausted", completed.exhausted());
            output.put("presentation", Map.of("blocks", blocks));output.put("truncated", result.truncated());output.put("warnings", warnings);
            output.put("queryHash", GraphPolicy.hash(plan.cypher()));output.put("durationMs", (System.nanoTime() - started) / 1_000_000);
            output.put("queryHashes", completed.attempts().stream().map(a->a.get("queryHash")).filter(h->!Objects.toString(h,"").isEmpty()).distinct().toList());
            if (GraphPolicy.encode(Map.of("graph", output, "chunks", chunks)).getBytes(java.nio.charset.StandardCharsets.UTF_8).length > number("T2C_MAX_RESULT_BYTES",262144,4096,2097152))
                throw new IllegalStateException("Semantic results and coverage exceed T2C_MAX_RESULT_BYTES; reduce rows or selected properties.");
            emit("text2cypher.execution.status", Map.of("status", status, "attempts", completed.attempts().size(), "answeredParts", evaluation.coverage().size()-unanswered.size(), "unansweredParts", unanswered.size()));
            emit("text2cypher.retrieval.completed", Map.of("rowCount", chunks.size(), "populatedRowCount", populatedRows, "dataStatus", dataStatus, "semanticStatus", status, "truncated", result.truncated(), "durationMs", output.get("durationMs")));
            return new RetrievalResult(request.query(), request.includeCitations(), chunks, output);
        } catch (Neo4jException failure) {
            emit("text2cypher.execution.status", Map.of("status", "FAILED", "reason", safeCode(failure)));
            throw databaseFailure(failure);
        } catch (java.util.concurrent.CancellationException cancelled) {
            emit("text2cypher.execution.status", Map.of("status", "CANCELLED"));throw cancelled;
        } catch (RuntimeException failure) {
            emit("text2cypher.execution.status", Map.of("status", "FAILED", "reason", failure.getClass().getSimpleName()));throw failure;
        }
    }

    private static boolean hasValues(Object value) {
        if (value == null) return false;
        if (value instanceof String text) return !text.isBlank();
        if (value instanceof Map<?, ?> map)
            return map.values().stream().anyMatch(Neo4JText2CypherRetrievalPlugin::hasValues);
        if (value instanceof Collection<?> items)
            return items.stream().anyMatch(Neo4JText2CypherRetrievalPlugin::hasValues);
        return true; // Zero and false are meaningful query results, not missing data.
    }

    private static String safeCode(Neo4jException failure) {
        String code = failure.code();
        return code != null && code.matches("[A-Za-z0-9_.]{1,160}") ? code : "NEO4J_ERROR";
    }

    private static IllegalStateException databaseFailure(Neo4jException failure) {
        return new IllegalStateException("Text-to-Cypher database operation failed (" + safeCode(failure) + "). Check the selected database, read permissions, schema and query timeout. Provider/query bodies are omitted.");
    }

    public void close() {
        try {
            if (model != null && model != suppliedModel) model.close();
        } finally {
            if (graph != null && graph != suppliedGraph) graph.close();
            model = null;
            graph = null;
            cachedSchema = null;
        }
    }
}
