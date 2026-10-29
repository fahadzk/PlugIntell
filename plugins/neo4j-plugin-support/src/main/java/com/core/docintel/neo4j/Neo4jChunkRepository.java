package com.core.docintel.neo4j;

import com.core.docintel.pipeline.api.EmbeddingCollection;
import com.core.docintel.pipeline.api.EmbeddedChunk;
import com.core.docintel.pipeline.api.RetrievedChunk;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Session;
import org.neo4j.driver.SessionConfig;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Neo4j 5-compatible repository shared by ingestion and retrieval plugin JARs. */
public final class Neo4jChunkRepository implements AutoCloseable {
    private final Neo4jConfig config;
    private final Driver driver;

    public Neo4jChunkRepository() { this(Neo4jConfig.fromEnvironment()); }

    public Neo4jChunkRepository(Neo4jConfig config) {
        this.config = config;
        this.driver = GraphDatabase.driver(config.uri(), AuthTokens.basic(config.username(), config.password()));
    }

    public void preflight(Integer embeddingDimensions) {
        if (embeddingDimensions != null && embeddingDimensions != config.vectorDimensions()) {
            throw new IllegalStateException("Embedding model produces " + embeddingDimensions + " dimensions, but "
                    + "NEO4J_VECTOR_DIMENSIONS is " + config.vectorDimensions() + ". Set it to the model's actual vector size and recreate the vector index if needed.");
        }
        try {
            driver.verifyConnectivity();
            try (Session session = driver.session(SessionConfig.forDatabase(config.database()))) {
                var indexes = session.run("SHOW VECTOR INDEXES YIELD name, state, labelsOrTypes, properties, options "
                                + "WHERE name = $name RETURN state, labelsOrTypes, properties, options",
                        Map.of("name", config.vectorIndex())).list();
                if (indexes.isEmpty()) {
                    throw new IllegalStateException("Neo4j vector index '" + config.vectorIndex() + "' is missing. Create it with: "
                            + config.indexCreationCommand());
                }
                var index = indexes.getFirst();
                String state = index.get("state").asString();
                if (!"ONLINE".equalsIgnoreCase(state)) {
                    throw new IllegalStateException("Neo4j vector index '" + config.vectorIndex() + "' is " + state + "; wait until it is ONLINE.");
                }
                List<String> labels = index.get("labelsOrTypes").asList(value -> value.asString());
                List<String> properties = index.get("properties").asList(value -> value.asString());
                if (!labels.contains("TextChunk") || !properties.contains("embedding")) {
                    throw new IllegalStateException("Neo4j vector index '" + config.vectorIndex()
                            + "' must index :TextChunk(embedding). Create it with: " + config.indexCreationCommand());
                }
                Map<String, Object> options = index.get("options").asMap();
                Object indexConfigValue = options.get("indexConfig");
                if (!(indexConfigValue instanceof Map<?, ?> indexConfig)) {
                    throw new IllegalStateException("Could not verify dimensions for Neo4j vector index '" + config.vectorIndex() + "'.");
                }
                Object dimensions = indexConfig.get("vector.dimensions");
                if (!(dimensions instanceof Number number) || number.intValue() != config.vectorDimensions()) {
                    throw new IllegalStateException("Neo4j vector index '" + config.vectorIndex() + "' is configured with dimensions '"
                            + dimensions + "' but NEO4J_VECTOR_DIMENSIONS is " + config.vectorDimensions()
                            + ". Recreate it with: " + config.indexCreationCommand());
                }
            }
        } catch (RuntimeException e) {
            if (e instanceof IllegalStateException && e.getMessage() != null
                    && (e.getMessage().contains("vector index") || e.getMessage().contains("Neo4j vector"))) throw e;
            throw new IllegalStateException("Neo4j preflight failed for " + config.uri() + " (database '" + config.database() + "'): " + e.getMessage(), e);
        }
    }

    public int store(EmbeddingCollection collection) {
        if (collection.embeddings().isEmpty()) return 0;
        List<Map<String, Object>> rows = new ArrayList<>(collection.embeddings().size());
        for (EmbeddedChunk embedding : collection.embeddings()) {
            if (embedding.vector().size() != config.vectorDimensions()) {
                throw new IllegalArgumentException("Embedding dimension mismatch for chunk " + embedding.chunk().id()
                        + ": NEO4J_VECTOR_DIMENSIONS is " + config.vectorDimensions() + " but vector has " + embedding.vector().size() + " values.");
            }
            Map<String, Object> row = new HashMap<>();
            row.put("id", embedding.chunk().id());
            row.put("documentId", embedding.chunk().documentId());
            row.put("sourceName", embedding.chunk().sourceName());
            row.put("pageNumber", embedding.chunk().pageNumber());
            row.put("ordinal", embedding.chunk().ordinal());
            row.put("text", embedding.chunk().text());
            row.put("embedding", embedding.vector().stream().map(Float::doubleValue).toList());
            rows.add(row);
        }
        try (Session session = driver.session(SessionConfig.forDatabase(config.database()))) {
            session.executeWrite(tx -> {
                tx.run("UNWIND $chunks AS item "
                                + "MERGE (c:TextChunk {id: item.id}) "
                                + "SET c.documentId = item.documentId, c.sourceName = item.sourceName, "
                                + "c.pageNumber = item.pageNumber, c.ordinal = item.ordinal, "
                                + "c.text = item.text, c.embedding = item.embedding",
                        Map.of("chunks", rows)).consume();
                return null;
            });
        } catch (RuntimeException e) {
            throw new IllegalStateException("Could not store embeddings in Neo4j: " + e.getMessage(), e);
        }
        return rows.size();
    }

    public List<RetrievedChunk> search(List<Float> vector, int topK) {
        if (vector.size() != config.vectorDimensions()) {
            throw new IllegalArgumentException("Query embedding has " + vector.size() + " dimensions but Neo4j index '"
                    + config.vectorIndex() + "' expects " + config.vectorDimensions() + ". Check the model/index configuration.");
        }
        List<Double> values = vector.stream().map(Float::doubleValue).toList();
        try (Session session = driver.session(SessionConfig.forDatabase(config.database()))) {
            return session.executeRead(tx -> tx.run(
                            "CALL db.index.vector.queryNodes($indexName, $topK, $embedding) YIELD node, score "
                                    + "RETURN node.id AS id, node.text AS text, node.sourceName AS sourceName, "
                                    + "node.pageNumber AS pageNumber, score",
                            Map.of("indexName", config.vectorIndex(), "topK", topK, "embedding", values))
                    .list(record -> new RetrievedChunk(record.get("id").asString(), record.get("text").asString(),
                            record.get("sourceName").asString(), record.get("pageNumber").asInt(), record.get("score").asDouble())));
        } catch (RuntimeException e) {
            throw new IllegalStateException("Neo4j vector search failed using index '" + config.vectorIndex()
                    + "' in database '" + config.database() + "': " + e.getMessage(), e);
        }
    }

    @Override public void close() { driver.close(); }
}
