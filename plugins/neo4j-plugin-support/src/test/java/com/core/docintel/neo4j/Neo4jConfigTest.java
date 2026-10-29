package com.core.docintel.neo4j;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class Neo4jConfigTest {
    @Test void exposesIndexSetupWithConfiguredDimensions() {
        var config = new Neo4jConfig("bolt://localhost:7687", "neo4j", "secret", "neo4j", "document_embedding_idx", 1024);
        assertTrue(config.indexCreationCommand().contains("`vector.dimensions`: 1024"));
        assertTrue(config.indexCreationCommand().contains("ON (c.embedding)"));
    }

    @Test void rejectsUnsafeIndexName() {
        assertThrows(IllegalArgumentException.class,
                () -> new Neo4jConfig("bolt://localhost:7687", "neo4j", "secret", "neo4j", "bad name; DROP", 768));
    }
}
