package com.core.docintel.pipeline.builder;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;
import jakarta.annotation.PostConstruct;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/** Small local metadata store. Published graph JSON is an immutable snapshot per version. */
@Singleton
public final class SqlitePipelineStore implements PipelineStore {
    private final Path databasePath;
    private final ObjectMapper json = new ObjectMapper();

    public SqlitePipelineStore(@Value("${pipeline.store.sqlite-path:./data/plugintell.db}") String path) {
        this.databasePath = Path.of(path).toAbsolutePath().normalize();
    }

    @PostConstruct
    void initialize() {
        try {
            Files.createDirectories(databasePath.getParent());
            Class.forName("org.sqlite.JDBC");
            try (Connection connection = connection(); Statement statement = connection.createStatement()) {
                statement.executeUpdate("CREATE TABLE IF NOT EXISTS pipelines (id TEXT PRIMARY KEY, name TEXT NOT NULL, type TEXT NOT NULL, revision INTEGER NOT NULL, published_version INTEGER, created_at TEXT NOT NULL, updated_at TEXT NOT NULL, definition_json TEXT NOT NULL)");
                statement.executeUpdate("CREATE TABLE IF NOT EXISTS pipeline_versions (pipeline_id TEXT NOT NULL, version INTEGER NOT NULL, published_at TEXT NOT NULL, definition_json TEXT NOT NULL, PRIMARY KEY (pipeline_id, version), FOREIGN KEY (pipeline_id) REFERENCES pipelines(id) ON DELETE CASCADE)");
                statement.executeUpdate("CREATE TABLE IF NOT EXISTS published_connection_snapshots (pipeline_id TEXT NOT NULL, version INTEGER NOT NULL, definition_json TEXT NOT NULL, PRIMARY KEY(pipeline_id,version), FOREIGN KEY(pipeline_id,version) REFERENCES pipeline_versions(pipeline_id,version) ON DELETE CASCADE)");
                statement.executeUpdate("PRAGMA journal_mode=WAL");
                statement.executeUpdate("PRAGMA foreign_keys=ON");
            }
        } catch (Exception e) {
            throw new IllegalStateException("Could not initialize pipeline metadata store at " + databasePath + ": " + e.getMessage(), e);
        }
    }

    @Override public synchronized List<PipelineRecord> list() {
        try (Connection c = connection(); PreparedStatement p = c.prepareStatement("SELECT * FROM pipelines ORDER BY updated_at DESC"); ResultSet r = p.executeQuery()) {
            List<PipelineRecord> records = new ArrayList<>(); while (r.next()) records.add(record(r)); return List.copyOf(records);
        } catch (SQLException e) { throw failure("list pipelines", e); }
    }

    @Override public synchronized Optional<PipelineRecord> get(String id) {
        try (Connection c = connection(); PreparedStatement p = c.prepareStatement("SELECT * FROM pipelines WHERE id=?")) {
            p.setString(1, id); try (ResultSet r = p.executeQuery()) { return r.next() ? Optional.of(record(r)) : Optional.empty(); }
        } catch (SQLException e) { throw failure("get pipeline", e); }
    }

    @Override public synchronized PipelineRecord create(PipelineDefinition definition) {
        String id = UUID.randomUUID().toString(); Instant now = Instant.now();
        try (Connection c = connection(); PreparedStatement p = c.prepareStatement("INSERT INTO pipelines(id,name,type,revision,published_version,created_at,updated_at,definition_json) VALUES(?,?,?,1,NULL,?,?,?)")) {
            p.setString(1,id); p.setString(2,definition.name()); p.setString(3,definition.type().name()); p.setString(4,now.toString()); p.setString(5,now.toString()); p.setString(6,encode(definition)); p.executeUpdate();
            return new PipelineRecord(id,1,null,now,now,definition);
        } catch (SQLException e) { throw failure("create pipeline", e); }
    }

    @Override public synchronized PipelineRecord update(String id, long expectedRevision, PipelineDefinition definition) {
        Instant now = Instant.now();
        try (Connection c = connection(); PreparedStatement p = c.prepareStatement("UPDATE pipelines SET name=?,type=?,revision=revision+1,updated_at=?,definition_json=? WHERE id=? AND revision=?")) {
            p.setString(1,definition.name()); p.setString(2,definition.type().name()); p.setString(3,now.toString()); p.setString(4,encode(definition)); p.setString(5,id); p.setLong(6,expectedRevision);
            if (p.executeUpdate() != 1) throw conflict(id, expectedRevision);
            return get(id).orElseThrow(() -> new IllegalArgumentException("Pipeline not found: " + id));
        } catch (SQLException e) { throw failure("update pipeline", e); }
    }

    @Override public synchronized PipelineVersionRecord publish(String id, long expectedRevision) {
        return publish(id,expectedRevision,null);
    }

    @Override public synchronized PipelineVersionRecord publish(String id, long expectedRevision, PipelineDefinition definition) {
        try (Connection c = connection()) {
            c.setAutoCommit(false);
            try {
                PipelineRecord current;
                try (PreparedStatement p = c.prepareStatement("SELECT * FROM pipelines WHERE id=?")) { p.setString(1,id); try (ResultSet r=p.executeQuery()) { if (!r.next()) throw new IllegalArgumentException("Pipeline not found: " + id); current=record(r); } }
                if (current.revision() != expectedRevision) throw conflict(id, expectedRevision);
                int next = current.publishedVersion() == null ? 1 : current.publishedVersion() + 1;
                PipelineDefinition published=definition==null?current.definition():definition;
                Instant now = Instant.now(); String snapshot = encode(published);
                try (PreparedStatement p=c.prepareStatement("INSERT INTO pipeline_versions(pipeline_id,version,published_at,definition_json) VALUES(?,?,?,?)")) { p.setString(1,id);p.setInt(2,next);p.setString(3,now.toString());p.setString(4,snapshot);p.executeUpdate(); }
                try (PreparedStatement p=c.prepareStatement("UPDATE pipelines SET published_version=?,revision=revision+1,updated_at=? WHERE id=? AND revision=?")) { p.setInt(1,next);p.setString(2,now.toString());p.setString(3,id);p.setLong(4,expectedRevision); if(p.executeUpdate()!=1) throw conflict(id,expectedRevision); }
                c.commit(); return new PipelineVersionRecord(id,next,now,published);
            } catch (RuntimeException | SQLException e) { c.rollback(); throw e; }
        } catch (SQLException e) { throw failure("publish pipeline",e); }
    }

    /** Preserve old reference-based versions before a profile changes, without rewriting their original JSON. */
    @Override public synchronized void freezePublishedConnections(String profileId,java.util.function.UnaryOperator<PipelineDefinition> snapshotter){
        try(Connection c=connection()){
            c.setAutoCommit(false);
            try{
                List<PipelineVersionRecord> pending=new ArrayList<>();
                try(PreparedStatement p=c.prepareStatement("SELECT v.* FROM pipeline_versions v LEFT JOIN published_connection_snapshots s ON s.pipeline_id=v.pipeline_id AND s.version=v.version WHERE s.version IS NULL");ResultSet rows=p.executeQuery()){
                    while(rows.next()){
                        PipelineVersionRecord version=version(rows);
                        if(version.definition().allNodes().stream().anyMatch(node->node!=null&&node.config()!=null&&profileId.equals(node.config().get("connectionRef"))))pending.add(version);
                    }
                }
                try(PreparedStatement p=c.prepareStatement("INSERT INTO published_connection_snapshots(pipeline_id,version,definition_json) VALUES(?,?,?)")){
                    for(PipelineVersionRecord version:pending){
                        p.setString(1,version.pipelineId());p.setInt(2,version.version());p.setString(3,encode(snapshotter.apply(version.definition())));p.addBatch();
                    }
                    p.executeBatch();
                }
                c.commit();
            }catch(RuntimeException|SQLException e){c.rollback();throw e;}
        }catch(SQLException e){throw failure("snapshot published connection settings",e);}
    }

    @Override public synchronized List<PipelineVersionRecord> versions(String id) {
        try (Connection c=connection(); PreparedStatement p=c.prepareStatement(versionQuery()+" WHERE v.pipeline_id=? ORDER BY v.version DESC")) { p.setString(1,id); try(ResultSet r=p.executeQuery()){List<PipelineVersionRecord> out=new ArrayList<>();while(r.next())out.add(version(r));return List.copyOf(out);} }
        catch(SQLException e){throw failure("list pipeline versions",e);}
    }

    @Override public synchronized Optional<PipelineVersionRecord> version(String id,int version) {
        try(Connection c=connection();PreparedStatement p=c.prepareStatement(versionQuery()+" WHERE v.pipeline_id=? AND v.version=?")){p.setString(1,id);p.setInt(2,version);try(ResultSet r=p.executeQuery()){return r.next()?Optional.of(version(r)):Optional.empty();}}
        catch(SQLException e){throw failure("get pipeline version",e);}
    }

    private static String versionQuery(){return "SELECT v.pipeline_id,v.version,v.published_at,COALESCE(s.definition_json,v.definition_json) AS definition_json FROM pipeline_versions v LEFT JOIN published_connection_snapshots s ON s.pipeline_id=v.pipeline_id AND s.version=v.version";}

    @Override public synchronized void delete(String id,long expectedRevision) {
        try(Connection c=connection();PreparedStatement p=c.prepareStatement("DELETE FROM pipelines WHERE id=? AND revision=?")){p.setString(1,id);p.setLong(2,expectedRevision);if(p.executeUpdate()!=1)throw conflict(id,expectedRevision);}
        catch(SQLException e){throw failure("delete pipeline",e);}
    }

    private Connection connection() throws SQLException { Connection c=DriverManager.getConnection("jdbc:sqlite:"+databasePath); try(Statement s=c.createStatement()){s.execute("PRAGMA foreign_keys=ON");} return c; }
    private PipelineRecord record(ResultSet r) throws SQLException { return new PipelineRecord(r.getString("id"),r.getLong("revision"),(Integer)r.getObject("published_version"),Instant.parse(r.getString("created_at")),Instant.parse(r.getString("updated_at")),decode(r.getString("definition_json"))); }
    private PipelineVersionRecord version(ResultSet r) throws SQLException { return new PipelineVersionRecord(r.getString("pipeline_id"),r.getInt("version"),Instant.parse(r.getString("published_at")),decode(r.getString("definition_json"))); }
    private String encode(PipelineDefinition definition) { try{return json.writeValueAsString(definition);}catch(JsonProcessingException e){throw new IllegalArgumentException("Pipeline definition cannot be serialized: "+e.getMessage(),e);} }
    private PipelineDefinition decode(String value) { try{return json.readValue(value,PipelineDefinition.class);}catch(Exception e){throw new IllegalStateException("Stored pipeline definition is invalid: "+e.getMessage(),e);} }
    private static PipelineConflictException conflict(String id,long revision){return new PipelineConflictException("Pipeline '"+id+"' changed since revision "+revision+"; reload and retry.");}
    private static IllegalStateException failure(String operation,SQLException e){return new IllegalStateException("Could not "+operation+": "+e.getMessage(),e);}
}
