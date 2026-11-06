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
public final class SqliteExecutionStore implements ExecutionStore {
    private final Path databasePath;
    private final ObjectMapper json=new ObjectMapper();
    public SqliteExecutionStore(@Value("${pipeline.store.sqlite-path:./data/plugintell.db}")String path){databasePath=Path.of(path).toAbsolutePath().normalize();}
    @PostConstruct void initialize(){try{Files.createDirectories(databasePath.getParent());Class.forName("org.sqlite.JDBC");try(Connection c=connection();Statement s=c.createStatement()){
        s.executeUpdate("CREATE TABLE IF NOT EXISTS executions (id TEXT PRIMARY KEY, pipeline_id TEXT NOT NULL, pipeline_version INTEGER NOT NULL, status TEXT NOT NULL, submitted_at TEXT NOT NULL, started_at TEXT, finished_at TEXT, output_json TEXT NOT NULL, error TEXT)");
        s.executeUpdate("CREATE TABLE IF NOT EXISTS execution_events (execution_id TEXT NOT NULL, sequence INTEGER NOT NULL, timestamp TEXT NOT NULL, node_id TEXT, status TEXT NOT NULL, message TEXT NOT NULL, duration_ms INTEGER NOT NULL, PRIMARY KEY(execution_id,sequence), FOREIGN KEY(execution_id) REFERENCES executions(id) ON DELETE CASCADE)");
        s.executeUpdate("UPDATE executions SET status='FAILED', finished_at='"+Instant.now()+"', error='Server restarted while execution was active.' WHERE status IN ('QUEUED','RUNNING','CANCELLING')");
    }}catch(Exception e){throw new IllegalStateException("Could not initialize execution store: "+e.getMessage(),e);}}
    @Override public synchronized void save(ExecutionSnapshot x){try(Connection c=connection();PreparedStatement p=c.prepareStatement("INSERT INTO executions(id,pipeline_id,pipeline_version,status,submitted_at,started_at,finished_at,output_json,error) VALUES(?,?,?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET status=excluded.status,started_at=excluded.started_at,finished_at=excluded.finished_at,output_json=excluded.output_json,error=excluded.error")){
        p.setString(1,x.id());p.setString(2,x.pipelineId());p.setInt(3,x.pipelineVersion());p.setString(4,x.status().name());p.setString(5,x.submittedAt().toString());p.setString(6,x.startedAt()==null?null:x.startedAt().toString());p.setString(7,x.finishedAt()==null?null:x.finishedAt().toString());p.setString(8,json.writeValueAsString(x.output()));p.setString(9,x.error());p.executeUpdate();
    }catch(Exception e){throw new IllegalStateException("Could not save execution: "+e.getMessage(),e);}}
    @Override public synchronized void append(String id,ExecutionEvent e){try(Connection c=connection();PreparedStatement p=c.prepareStatement("INSERT INTO execution_events(execution_id,sequence,timestamp,node_id,status,message,duration_ms) VALUES(?,?,?,?,?,?,?)")){
        p.setString(1,id);p.setLong(2,e.sequence());p.setString(3,e.timestamp().toString());p.setString(4,e.nodeId());p.setString(5,e.status());p.setString(6,e.message());p.setLong(7,e.durationMillis());p.executeUpdate();
    }catch(Exception ex){throw new IllegalStateException("Could not store execution event: "+ex.getMessage(),ex);}}
    @Override public synchronized Optional<ExecutionSnapshot> get(String id){try(Connection c=connection();PreparedStatement p=c.prepareStatement("SELECT * FROM executions WHERE id=?")){p.setString(1,id);try(ResultSet r=p.executeQuery()){return r.next()?Optional.of(read(r)):Optional.empty();}}catch(Exception e){throw new IllegalStateException("Could not read execution: "+e.getMessage(),e);}}
    @Override public synchronized List<ExecutionSnapshot> list(){try(Connection c=connection();PreparedStatement p=c.prepareStatement("SELECT * FROM executions ORDER BY submitted_at DESC LIMIT 500");ResultSet r=p.executeQuery()){List<ExecutionSnapshot> out=new ArrayList<>();while(r.next())out.add(read(r));return List.copyOf(out);}catch(Exception e){throw new IllegalStateException("Could not list executions: "+e.getMessage(),e);}}
    @Override public synchronized List<ExecutionEvent> events(String id){try(Connection c=connection();PreparedStatement p=c.prepareStatement("SELECT * FROM execution_events WHERE execution_id=? ORDER BY sequence")){p.setString(1,id);try(ResultSet r=p.executeQuery()){List<ExecutionEvent> out=new ArrayList<>();while(r.next())out.add(new ExecutionEvent(r.getLong("sequence"),Instant.parse(r.getString("timestamp")),r.getString("node_id"),r.getString("status"),r.getString("message"),r.getLong("duration_ms")));return List.copyOf(out);}}catch(Exception e){throw new IllegalStateException("Could not read execution events: "+e.getMessage(),e);}}
    @Override public synchronized List<ExecutionEvent> events(String id,long afterSequence){try(Connection c=connection();PreparedStatement p=c.prepareStatement("SELECT * FROM execution_events WHERE execution_id=? AND sequence>? ORDER BY sequence")){p.setString(1,id);p.setLong(2,afterSequence);try(ResultSet r=p.executeQuery()){List<ExecutionEvent> out=new ArrayList<>();while(r.next())out.add(new ExecutionEvent(r.getLong("sequence"),Instant.parse(r.getString("timestamp")),r.getString("node_id"),r.getString("status"),r.getString("message"),r.getLong("duration_ms")));return List.copyOf(out);}}catch(Exception e){throw new IllegalStateException("Could not read execution events: "+e.getMessage(),e);}}
    private ExecutionSnapshot read(ResultSet r)throws Exception{String start=r.getString("started_at"),finish=r.getString("finished_at");return new ExecutionSnapshot(r.getString("id"),r.getString("pipeline_id"),r.getInt("pipeline_version"),ExecutionStatus.valueOf(r.getString("status")),Instant.parse(r.getString("submitted_at")),start==null?null:Instant.parse(start),finish==null?null:Instant.parse(finish),json.readValue(r.getString("output_json"),Map.class),r.getString("error"));}
    private Connection connection()throws SQLException{Connection c=DriverManager.getConnection("jdbc:sqlite:"+databasePath);try(Statement s=c.createStatement()){s.execute("PRAGMA foreign_keys=ON");}return c;}
}
