package com.core.docintel.pipeline.builder;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class SqlitePipelineStoreTest {
    @TempDir Path temp;

    @Test void persistsDraftAndKeepsPublishedSnapshotImmutable() {
        SqlitePipelineStore store = store();
        PipelineRecord created = store.create(definition("draft"));
        assertEquals(1, created.revision());
        PipelineVersionRecord published = store.publish(created.id(), created.revision());
        assertEquals(1, published.version());
        PipelineRecord edited = store.update(created.id(), 2, definition("edited"));
        assertEquals(3, edited.revision());
        assertEquals("edited", store.get(created.id()).orElseThrow().definition().name());
        assertEquals("draft", store.version(created.id(), 1).orElseThrow().definition().name());
        assertThrows(PipelineConflictException.class, () -> store.update(created.id(), 1, definition("stale")));
    }

    @Test void canReopenDatabaseAndListPersistedDefinitions() {
        SqlitePipelineStore first = store();
        PipelineRecord created = first.create(definition("persisted"));
        SqlitePipelineStore reopened = store();
        assertEquals(created.id(), reopened.list().getFirst().id());
        assertEquals("persisted", reopened.get(created.id()).orElseThrow().definition().name());
    }

    private SqlitePipelineStore store() {
        SqlitePipelineStore store = new SqlitePipelineStore(temp.resolve("studio.db").toString());
        store.initialize();
        return store;
    }
    private static PipelineDefinition definition(String name) {
        return new PipelineDefinition(name, PipelineType.DOCUMENT_INGESTION, 1,
                List.of(new PipelineNode("source", "source", "1.0.0", Map.of("path", "/docs"))), List.of());
    }
}
