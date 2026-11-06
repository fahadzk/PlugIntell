package com.core.docintel.pipeline.builder;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micronaut.context.annotation.Value;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Singleton;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.util.*;

@Singleton
public final class SqliteConnectionProfileStore implements ConnectionProfileStore {
    private final Path databasePath;
    private final ObjectMapper json = new ObjectMapper();

    public SqliteConnectionProfileStore(@Value("${pipeline.store.sqlite-path:./data/plugintell.db}") String path) {
        databasePath = Path.of(path).toAbsolutePath().normalize();
    }

    @PostConstruct void initialize() {
        try {
            Files.createDirectories(databasePath.getParent());
            Class.forName("org.sqlite.JDBC");
            try (Connection connection = connection(); Statement statement = connection.createStatement()) {
                statement.executeUpdate("CREATE TABLE IF NOT EXISTS connection_profiles (id TEXT PRIMARY KEY, name TEXT NOT NULL, plugin_id TEXT NOT NULL, plugin_version TEXT NOT NULL, revision INTEGER NOT NULL, created_at TEXT NOT NULL, updated_at TEXT NOT NULL, config_json TEXT NOT NULL, plugin_configs_json TEXT)");
                try { statement.executeUpdate("ALTER TABLE connection_profiles ADD COLUMN plugin_configs_json TEXT"); }
                catch (SQLException alreadyMigrated) { if (!Objects.toString(alreadyMigrated.getMessage(), "").toLowerCase(Locale.ROOT).contains("duplicate column")) throw alreadyMigrated; }
                statement.executeUpdate("CREATE INDEX IF NOT EXISTS connection_profiles_plugin_idx ON connection_profiles(plugin_id)");
                migrateWrappedProfiles(connection);
            }
        } catch (Exception e) { throw new IllegalStateException("Could not initialize connection profile store: " + e.getMessage(), e); }
    }

    @Override public synchronized List<ConnectionProfileRecord> list() {
        try (Connection c=connection(); PreparedStatement p=c.prepareStatement("SELECT * FROM connection_profiles ORDER BY name COLLATE NOCASE"); ResultSet r=p.executeQuery()) {
            List<ConnectionProfileRecord> records=new ArrayList<>(); while(r.next())records.add(read(r)); return List.copyOf(records);
        } catch(SQLException e){throw failure("list connection profiles",e);}
    }
    @Override public synchronized Optional<ConnectionProfileRecord> get(String id) {
        try(Connection c=connection();PreparedStatement p=c.prepareStatement("SELECT * FROM connection_profiles WHERE id=?")){p.setString(1,id);try(ResultSet r=p.executeQuery()){return r.next()?Optional.of(read(r)):Optional.empty();}}
        catch(SQLException e){throw failure("get connection profile",e);}
    }
    @Override public synchronized ConnectionProfileRecord create(String name,String pluginId,String pluginVersion,Map<String,Object> config) {
        return createMultiProvider(name,pluginId,pluginVersion,Map.of(pluginId,new ConnectionPluginSettings(pluginVersion,config)));
    }
    @Override public synchronized ConnectionProfileRecord createMultiProvider(String name,String pluginId,String pluginVersion,Map<String,ConnectionPluginSettings> pluginConfigs) {
        String id=UUID.randomUUID().toString();Instant now=Instant.now();
        try(Connection c=connection();PreparedStatement p=c.prepareStatement("INSERT INTO connection_profiles(id,name,plugin_id,plugin_version,revision,created_at,updated_at,config_json,plugin_configs_json) VALUES(?,?,?, ?,1,?,?,?,?)")){
            p.setString(1,id);p.setString(2,name);p.setString(3,pluginId);p.setString(4,pluginVersion);p.setString(5,now.toString());p.setString(6,now.toString());p.setString(7,encodeConfig(pluginConfigs.get(pluginId).config()));p.setString(8,encode(pluginConfigs));p.executeUpdate();
            return record(id,name,pluginId,pluginVersion,1,now,now,pluginConfigs);
        }catch(SQLException e){throw failure("create connection profile",e);}
    }
    @Override public synchronized ConnectionProfileRecord update(String id,long expectedRevision,String name,Map<String,Object> config) {
        ConnectionProfileRecord current=get(id).orElseThrow(()->new IllegalArgumentException("Connection profile not found."));
        Map<String,ConnectionPluginSettings> configs=new LinkedHashMap<>(current.pluginConfigs());
        configs.put(current.pluginId(),new ConnectionPluginSettings(current.pluginVersion(),config));
        return updateMultiProvider(id,expectedRevision,name,current.pluginId(),current.pluginVersion(),configs);
    }
    @Override public synchronized ConnectionProfileRecord updateMultiProvider(String id,long expectedRevision,String name,String pluginId,String pluginVersion,Map<String,ConnectionPluginSettings> pluginConfigs) {
        Instant now=Instant.now();
        try(Connection c=connection();PreparedStatement p=c.prepareStatement("UPDATE connection_profiles SET name=?,plugin_id=?,plugin_version=?,revision=revision+1,updated_at=?,config_json=?,plugin_configs_json=? WHERE id=? AND revision=?")){
            p.setString(1,name);p.setString(2,pluginId);p.setString(3,pluginVersion);p.setString(4,now.toString());p.setString(5,encodeConfig(pluginConfigs.get(pluginId).config()));p.setString(6,encode(pluginConfigs));p.setString(7,id);p.setLong(8,expectedRevision);
            if(p.executeUpdate()!=1)throw new ConnectionProfileConflictException("Connection profile changed since revision "+expectedRevision+"; reload and retry.");
            return get(id).orElseThrow(()->new IllegalArgumentException("Connection profile not found."));
        }catch(SQLException e){throw failure("update connection profile",e);}
    }
    @Override public synchronized void delete(String id,long expectedRevision) {
        try(Connection c=connection();PreparedStatement p=c.prepareStatement("DELETE FROM connection_profiles WHERE id=? AND revision=?")){p.setString(1,id);p.setLong(2,expectedRevision);if(p.executeUpdate()!=1)throw new ConnectionProfileConflictException("Connection profile changed since revision "+expectedRevision+"; reload and retry.");}
        catch(SQLException e){throw failure("delete connection profile",e);}
    }
    private Connection connection()throws SQLException{return DriverManager.getConnection("jdbc:sqlite:"+databasePath);}
    private ConnectionProfileRecord read(ResultSet r)throws SQLException{
        String primaryId=r.getString("plugin_id"),primaryVersion=r.getString("plugin_version");
        String providerJson=r.getString("plugin_configs_json");
        Map<String,ConnectionPluginSettings> configs=providerJson==null?Map.of(primaryId,new ConnectionPluginSettings(primaryVersion,decodeConfig(r.getString("config_json")))):decode(providerJson);
        return record(r.getString("id"),r.getString("name"),primaryId,primaryVersion,r.getLong("revision"),Instant.parse(r.getString("created_at")),Instant.parse(r.getString("updated_at")),configs);
    }
    private static ConnectionProfileRecord record(String id,String name,String primaryId,String primaryVersion,long revision,Instant created,Instant updated,Map<String,ConnectionPluginSettings> configs){
        ConnectionPluginSettings primary=configs.get(primaryId);
        return new ConnectionProfileRecord(id,name,primaryId,primaryVersion,revision,created,updated,
                primary==null?Map.of():primary.config(),Map.copyOf(configs));
    }
    private String encode(Map<String,ConnectionPluginSettings> configs){try{return json.writeValueAsString(configs);}catch(Exception e){throw new IllegalArgumentException("Connection profile configuration cannot be serialized.",e);}}
    private String encodeConfig(Map<String,Object> config){try{return json.writeValueAsString(config);}catch(Exception e){throw new IllegalArgumentException("Connection profile configuration cannot be serialized.",e);}}
    @SuppressWarnings("unchecked") private Map<String,Object> decodeConfig(String value){try{return (Map<String,Object>)json.readValue(value,Map.class);}catch(Exception e){throw new IllegalStateException("Stored connection profile configuration is invalid.",e);}}
    @SuppressWarnings("unchecked") private Map<String,ConnectionPluginSettings> decode(String value){
        try{
            Map<String,Object> raw=json.readValue(value,Map.class);Object stored=raw.get("pluginConfigs");
            if(stored instanceof Map<?,?> providers)raw=(Map<String,Object>)providers;
            Map<String,ConnectionPluginSettings> result=new LinkedHashMap<>();raw.forEach((id,setting)->{if(setting instanceof Map<?,?> fields){String version=Objects.toString(fields.get("pluginVersion"),"");Object config=fields.get("config");if(config instanceof Map<?,?> configMap){Map<String,Object> values=new LinkedHashMap<>();configMap.forEach((key,item)->{if(key instanceof String text)values.put(text,item);});result.put(id,new ConnectionPluginSettings(version,Map.copyOf(values)));}}});return Map.copyOf(result);
        }catch(Exception e){throw new IllegalStateException("Stored connection profile configuration is invalid.",e);}
    }
    private void migrateWrappedProfiles(Connection c)throws SQLException{
        record Migration(String id,String configJson,String providerJson) { }
        List<Migration> migrations=new ArrayList<>();
        try(PreparedStatement query=c.prepareStatement("SELECT id,plugin_id,plugin_version,config_json FROM connection_profiles WHERE plugin_configs_json IS NULL");ResultSet rows=query.executeQuery()){
            while(rows.next()){
                String raw=rows.getString("config_json");Map<String,Object> parsed=decodeConfig(raw);if(!(parsed.get("pluginConfigs") instanceof Map<?,?>))continue;
                String id=rows.getString("id"),primaryId=rows.getString("plugin_id"),primaryVersion=rows.getString("plugin_version");Map<String,ConnectionPluginSettings> configs=decode(raw);ConnectionPluginSettings primary=configs.get(primaryId);if(primary==null)continue;
                migrations.add(new Migration(id,encodeConfig(primary.config()),encode(configs)));
            }
        }
        try(PreparedStatement update=c.prepareStatement("UPDATE connection_profiles SET config_json=?,plugin_configs_json=? WHERE id=?")){
            for(Migration migration:migrations){update.setString(1,migration.configJson());update.setString(2,migration.providerJson());update.setString(3,migration.id());update.addBatch();}update.executeBatch();
        }
    }
    private static IllegalStateException failure(String operation,SQLException e){return new IllegalStateException("Could not "+operation+": "+e.getMessage(),e);}
}
