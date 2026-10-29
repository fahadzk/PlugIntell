package com.core.docintel.neo4j;

public record Neo4jConfig(String uri, String username, String password, String database,
                          String vectorIndex, int vectorDimensions) {
    public Neo4jConfig {
        if (uri == null || uri.isBlank()) throw new IllegalArgumentException("NEO4J_URI is required.");
        if (username == null || username.isBlank()) throw new IllegalArgumentException("NEO4J_USERNAME is required.");
        if (password == null || password.isBlank()) throw new IllegalArgumentException("NEO4J_PASSWORD is required.");
        if (database == null || database.isBlank()) throw new IllegalArgumentException("NEO4J_DATABASE must not be blank.");
        if (vectorIndex == null || !vectorIndex.matches("[A-Za-z][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("NEO4J_VECTOR_INDEX must be a simple identifier starting with a letter.");
        }
        if (vectorDimensions < 1 || vectorDimensions > 4096) throw new IllegalArgumentException("NEO4J_VECTOR_DIMENSIONS must be between 1 and 4096.");
    }

    public static Neo4jConfig fromEnvironment() {
        return new Neo4jConfig(
                System.getenv().getOrDefault("NEO4J_URI", "bolt://localhost:7687"),
                System.getenv().getOrDefault("NEO4J_USERNAME", "neo4j"),
                System.getenv("NEO4J_PASSWORD"),
                System.getenv().getOrDefault("NEO4J_DATABASE", "neo4j"),
                System.getenv().getOrDefault("NEO4J_VECTOR_INDEX", "document_embedding_idx"),
                Integer.parseInt(System.getenv().getOrDefault("NEO4J_VECTOR_DIMENSIONS", "768")));
    }

    public String indexCreationCommand() {
        return "CREATE VECTOR INDEX " + vectorIndex + " IF NOT EXISTS FOR (c:TextChunk) ON (c.embedding) "
                + "OPTIONS { indexConfig: { `vector.dimensions`: " + vectorDimensions
                + ", `vector.similarity_function`: 'cosine' } };";
    }

    @Override public String toString() {
        return "Neo4jConfig[uri=" + uri + ", username=" + username + ", password=<redacted>, database="
                + database + ", vectorIndex=" + vectorIndex + ", vectorDimensions=" + vectorDimensions + "]";
    }
}
