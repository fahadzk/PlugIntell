package com.core.docintel.pipeline.builder;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class SqliteConnectionProfileStoreTest {
    @TempDir Path temp;

    @Test void persistsProfilesAndUsesOptimisticRevisionChecks(){
        var first=store();
        var created=first.create("Primary Ollama","ollama-answer","1.0.0",Map.of("OLLAMA_CHAT_MODEL","qwen"));
        assertEquals(1,created.revision());
        var updated=first.update(created.id(),created.revision(),"Local Ollama",Map.of("OLLAMA_CHAT_MODEL","llama"));
        assertEquals(2,updated.revision());
        assertThrows(ConnectionProfileConflictException.class,()->first.update(created.id(),1,"stale",Map.of()));

        var reopened=store();
        assertEquals("Local Ollama",reopened.get(created.id()).orElseThrow().name());
        assertEquals("llama",reopened.list().getFirst().config().get("OLLAMA_CHAT_MODEL"));
        reopened.delete(created.id(),2);
        assertTrue(reopened.list().isEmpty());
    }

    @Test void persistsSettingsForSeveralPluginsInOneProfile(){
        var first=store();
        var settings=Map.of(
                "neo4j-retrieval",new ConnectionPluginSettings("1.0.0",Map.of("NEO4J_PASSWORD","secret://env/NEO4J_PASSWORD")),
                "ollama-embedding",new ConnectionPluginSettings("1.0.0",Map.of("OLLAMA_EMBEDDING_MODEL","nomic-embed-text")));
        var created=first.createMultiProvider("Home RAG","neo4j-retrieval","1.0.0",settings);
        var reopened=store();
        var profile=reopened.get(created.id()).orElseThrow();
        assertEquals("secret://env/NEO4J_PASSWORD",profile.pluginConfigs().get("neo4j-retrieval").config().get("NEO4J_PASSWORD"));
        assertEquals("nomic-embed-text",profile.pluginConfigs().get("ollama-embedding").config().get("OLLAMA_EMBEDDING_MODEL"));
        var changed=new java.util.LinkedHashMap<>(profile.pluginConfigs());
        changed.put("ollama-embedding",new ConnectionPluginSettings("1.0.0",Map.of("OLLAMA_EMBEDDING_MODEL","nomic-embed-text:latest")));
        reopened.updateMultiProvider(profile.id(),profile.revision(),profile.name(),profile.pluginId(),profile.pluginVersion(),changed);
        assertEquals("nomic-embed-text:latest",first.get(profile.id()).orElseThrow().pluginConfigs().get("ollama-embedding").config().get("OLLAMA_EMBEDDING_MODEL"));
    }

    @Test void migratesPreviouslyWrappedProfilesWithoutBreakingLegacyConfigColumn()throws Exception{
        Path database=temp.resolve("wrapped-legacy.db");
        try(var connection=DriverManager.getConnection("jdbc:sqlite:"+database);var statement=connection.createStatement()){
            statement.executeUpdate("CREATE TABLE connection_profiles (id TEXT PRIMARY KEY, name TEXT NOT NULL, plugin_id TEXT NOT NULL, plugin_version TEXT NOT NULL, revision INTEGER NOT NULL, created_at TEXT NOT NULL, updated_at TEXT NOT NULL, config_json TEXT NOT NULL)");
            String wrapped="{\"pluginConfigs\":{\"neo4j-retrieval\":{\"pluginVersion\":\"1.0.0\",\"config\":{\"NEO4J_PASSWORD\":\"secret://env/NEO4J_PASSWORD\"}},\"ollama-embedding\":{\"pluginVersion\":\"1.0.0\",\"config\":{\"OLLAMA_EMBEDDING_MODEL\":\"nomic-embed-text\"}}}}";
            try(var insert=connection.prepareStatement("INSERT INTO connection_profiles VALUES(?,?,?,?,?,?,?,?)")){
                insert.setString(1,"legacy-profile");insert.setString(2,"Legacy profile");insert.setString(3,"neo4j-retrieval");insert.setString(4,"1.0.0");insert.setLong(5,4);insert.setString(6,"2026-01-01T00:00:00Z");insert.setString(7,"2026-01-01T00:00:00Z");insert.setString(8,wrapped);insert.executeUpdate();
            }
        }
        var migrated=new SqliteConnectionProfileStore(database.toString());migrated.initialize();
        var profile=migrated.get("legacy-profile").orElseThrow();
        assertFalse(profile.defaultProfile());
        assertEquals("secret://env/NEO4J_PASSWORD",profile.config().get("NEO4J_PASSWORD"));
        assertEquals("nomic-embed-text",profile.pluginConfigs().get("ollama-embedding").config().get("OLLAMA_EMBEDDING_MODEL"));
        try(var connection=DriverManager.getConnection("jdbc:sqlite:"+database);var query=connection.createStatement().executeQuery("SELECT config_json,plugin_configs_json FROM connection_profiles WHERE id='legacy-profile'")){
            assertTrue(query.getString("config_json").contains("NEO4J_PASSWORD"));
            assertTrue(query.getString("plugin_configs_json").contains("ollama-embedding"));
        }
    }

    @Test void persistsOneExplicitDefaultAndKeepsItOnFailedUpdates(){
        var store=store();
        var a=store.create("A","ollama-answer","1.0.0",Map.of("OLLAMA_CHAT_MODEL","one"));
        var b=store.create("B","ollama-answer","1.0.0",Map.of("OLLAMA_CHAT_MODEL","two"));
        assertTrue(store.list().stream().noneMatch(ConnectionProfileRecord::defaultProfile));
        var marked=store.makeDefault(a.id(),a.revision());
        assertTrue(marked.defaultProfile());assertEquals(2,marked.revision());
        assertTrue(store().get(a.id()).orElseThrow().defaultProfile());
        assertThrows(ConnectionProfileConflictException.class,()->store.makeDefault(b.id(),99));
        assertThrows(java.util.NoSuchElementException.class,()->store.makeDefault("missing",1));
        assertTrue(store.get(a.id()).orElseThrow().defaultProfile());
        var changed=store.makeDefault(b.id(),b.revision());
        assertEquals(1,store.list().stream().filter(ConnectionProfileRecord::defaultProfile).count());
        assertFalse(store.get(a.id()).orElseThrow().defaultProfile());
        assertEquals(changed.revision(),store.makeDefault(b.id(),changed.revision()).revision());
        store.update(b.id(),changed.revision(),"Renamed",Map.of("OLLAMA_CHAT_MODEL","updated"));
        var updated=store.get(b.id()).orElseThrow();assertTrue(updated.defaultProfile());
        store.delete(b.id(),updated.revision());assertTrue(store.list().stream().noneMatch(ConnectionProfileRecord::defaultProfile));
    }

    private SqliteConnectionProfileStore store(){var store=new SqliteConnectionProfileStore(temp.resolve("studio.db").toString());store.initialize();return store;}
}
