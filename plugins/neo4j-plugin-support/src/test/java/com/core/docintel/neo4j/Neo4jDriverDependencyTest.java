package com.core.docintel.neo4j;

import org.junit.jupiter.api.Test;
import org.neo4j.driver.*;
import static org.junit.jupiter.api.Assertions.*;

class Neo4jDriverDependencyTest {
    @Test void constructsAndClosesDriverWithSharedNettyWithoutConnecting() {
        // Driver construction initializes its Netty/TLS infrastructure, but no
        // query or connectivity check is issued and no database is required.
        try (Driver driver=GraphDatabase.driver("bolt://127.0.0.1:1", AuthTokens.none(),
                Config.builder().withEventLoopThreads(1).withEncryption().build())) {
            assertNotNull(driver);
        }
    }
}
