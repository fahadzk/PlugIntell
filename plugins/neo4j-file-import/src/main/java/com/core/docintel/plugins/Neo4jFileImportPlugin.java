package com.core.docintel.plugins;
import com.core.docintel.pipeline.api.*;
import com.core.docintel.neo4j.Neo4jConfig;
import org.neo4j.driver.*;
import org.neo4j.driver.summary.SummaryCounters;
import java.io.File;
import java.net.URI;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** A standalone File -> GraphImportResult ingestion stage. */
public final class Neo4jFileImportPlugin extends AbstractPlugin implements ConfigurablePlugin,ManagedPlugin {
    private Driver driver;private final boolean supplied;private Neo4jConfig config;
    private String format="AUTO",namespace="default";private int timeout=120,batch=500,maxBytes=52428800;private boolean provision=true;
    public Neo4jFileImportPlugin(){this(null,PluginMessages.noop());}
    public Neo4jFileImportPlugin(PluginMessages messages){this(null,messages);}
    Neo4jFileImportPlugin(Driver driver,PluginMessages messages){super(messages);this.driver=driver;supplied=driver!=null;}
    public PluginManifest manifest(){return new PluginManifest("neo4j-file-import","Neo4j CSV / Cypher Import","1.0.0","1.0",getClass().getName(),"File","GraphImportResult");}
    private static String text(Map<String,Object> values,String key,String fallback){return Objects.toString(values.getOrDefault(key,System.getenv().getOrDefault(key,fallback)),"");}
    public void configure(Map<String,Object> values){
        var next=new Neo4jConfig(text(values,"NEO4J_URI","bolt://localhost:7687"),text(values,"NEO4J_USERNAME","neo4j"),text(values,"NEO4J_PASSWORD",""),text(values,"NEO4J_DATABASE","neo4j"),"unused",1);
        try{var uri=URI.create(next.uri());if(uri.getHost()==null||uri.getUserInfo()!=null||uri.getQuery()!=null||uri.getFragment()!=null||!Set.of("bolt","bolt+s","bolt+ssc","neo4j","neo4j+s","neo4j+ssc").contains(uri.getScheme()))throw new IllegalArgumentException();}catch(Exception e){throw new IllegalArgumentException("NEO4J_URI must be a Neo4j server URI without embedded credentials.");}
        if(next.password().startsWith("secret://"))throw new IllegalArgumentException("NEO4J_PASSWORD must be resolved by the host.");
        String mode=text(values,"NEO4J_IMPORT_FORMAT","AUTO").toUpperCase(Locale.ROOT),ns=text(values,"NEO4J_IMPORT_NAMESPACE","default");
        if(!Set.of("AUTO","CSV","CYPHER").contains(mode))throw new IllegalArgumentException("NEO4J_IMPORT_FORMAT must be AUTO, CSV or CYPHER.");
        if(!ns.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}"))throw new IllegalArgumentException("Invalid NEO4J_IMPORT_NAMESPACE.");
        int seconds=number(values,"NEO4J_IMPORT_TIMEOUT_SECONDS",120,1,600),size=number(values,"NEO4J_IMPORT_BATCH_SIZE",500,1,10000),limit=number(values,"NEO4J_IMPORT_MAX_BYTES",52428800,1,104857600);
        String create=text(values,"NEO4J_IMPORT_PROVISION_SCHEMA","true");if(!Set.of("true","false").contains(create))throw new IllegalArgumentException("NEO4J_IMPORT_PROVISION_SCHEMA must be true or false.");
        if(driver!=null&&!supplied){driver.close();driver=null;}config=next;format=mode;namespace=ns;timeout=seconds;batch=size;maxBytes=limit;provision=Boolean.parseBoolean(create);
    }
    private static int number(Map<String,Object> c,String k,int fallback,int min,int max){int n=Integer.parseInt(text(c,k,""+fallback));if(n<min||n>max)throw new IllegalArgumentException(k+" outside allowed range.");return n;}
    private Driver driver(){if(driver==null)driver=GraphDatabase.driver(config.uri(),AuthTokens.basic(config.username(),config.password()),Config.builder().withConnectionTimeout(timeout,TimeUnit.SECONDS).build());return driver;}
    public Object execute(Object input){
        if(!(input instanceof File file))throw new IllegalArgumentException("Neo4j file import expects a File.");
        if(config==null)configure(Map.of());
        String mode=format;if(mode.equals("AUTO")){String name=file.getName().toLowerCase(Locale.ROOT);mode=name.endsWith(".csv")?"CSV":name.endsWith(".cypher")||name.endsWith(".cql")?"CYPHER":"";if(mode.isEmpty())throw new IllegalArgumentException("Use a .csv, .cypher or .cql file, or set NEO4J_IMPORT_FORMAT.");}
        String raw;
        try{if(!Files.isRegularFile(file.toPath()))throw new IllegalArgumentException("Import file must be a regular file.");if(Files.size(file.toPath())>maxBytes)throw new IllegalArgumentException("Import file exceeds NEO4J_IMPORT_MAX_BYTES.");try(var stream=Files.newInputStream(file.toPath())){byte[] bytes=stream.readNBytes(maxBytes+1);if(bytes.length>maxBytes)throw new IllegalArgumentException("Import file exceeds NEO4J_IMPORT_MAX_BYTES.");raw=java.nio.charset.StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();}}
        catch(java.io.IOException e){throw new IllegalArgumentException("Cannot read import file as UTF-8.");}
        GraphImportFiles.Csv csv=mode.equals("CSV")?GraphImportFiles.csv(raw):null;List<String> statements=csv==null?GraphImportFiles.statements(raw):List.of();
        long total=csv==null?statements.size():csv.nodes().size()+csv.relationships().size();progress(0,total,csv==null?"statements staged":"rows staged");info("Importing "+mode+" file into database "+config.database()+".");
        Counts counts=new Counts();String selected=mode;
        try(var session=driver().session(SessionConfig.builder().withDatabase(config.database()).withDefaultAccessMode(org.neo4j.driver.AccessMode.WRITE).build())){
            if(csv!=null)prepareCsv(session);
            // Explicit transaction: arbitrary scripts are never automatically retried.
            try(var tx=session.beginTransaction(TransactionConfig.builder().withTimeout(Duration.ofSeconds(timeout)).build())){
                if(csv==null){int done=0;for(String statement:statements){cancelled();counts.add(tx.run(statement,Map.of("namespace",namespace)).consume().counters());progress(++done,total,"statements staged");}}
                else{
                    long done=0;
                    for(var group:groups(csv.nodes()).entrySet())done=write(tx,"UNWIND $rows AS row MERGE (n:ImportedGraphNode {namespace:$namespace,id:row.id}) SET n:`"+group.getKey()+"` SET n += row.properties RETURN count(*) AS written",group.getValue(),counts,done,total);
                    for(var group:groups(csv.relationships()).entrySet())done=write(tx,"UNWIND $rows AS row MATCH (s:ImportedGraphNode {namespace:$namespace,id:row.source}),(t:ImportedGraphNode {namespace:$namespace,id:row.target}) MERGE (s)-[r:`"+group.getKey()+"` {id:row.id,namespace:$namespace}]->(t) SET r += row.properties RETURN count(*) AS written",group.getValue(),counts,done,total);
                }
                cancelled();tx.commit();
            }
        }catch(CancellationException e){throw e;}catch(RuntimeException e){if(e instanceof IllegalArgumentException&&Objects.toString(e.getMessage(),"").startsWith("CSV"))throw e;String code=e instanceof org.neo4j.driver.exceptions.Neo4jException n?n.code():"transaction failure";if(!code.matches("[A-Za-z0-9. ]+"))code="database failure";throw new IllegalStateException("Neo4j import failed ("+code+"). Check credentials, permissions, database, file syntax and CSV endpoint IDs. File text and provider details are omitted.");}
        info("Import committed successfully.");emit("graph.import.completed",Map.of("nodesCreated",counts.nodes,"relationshipsCreated",counts.relations,"statementsExecuted",counts.statements));
        return new GraphImportResult(file.getName(),selected,config.database(),counts.nodes,counts.relations,counts.nodesDeleted,counts.relationsDeleted,counts.properties,counts.statements);
    }
    private void prepareCsv(Session session){
        if(provision)session.run("CREATE CONSTRAINT imported_graph_node IF NOT EXISTS FOR (n:ImportedGraphNode) REQUIRE (n.namespace,n.id) IS UNIQUE").consume();
        boolean found=session.run("SHOW CONSTRAINTS YIELD entityType,type,labelsOrTypes,properties RETURN entityType,type,labelsOrTypes,properties").list().stream().anyMatch(r->r.get("entityType").asString().equals("NODE")&&Set.of("UNIQUENESS","NODE_PROPERTY_UNIQUENESS","NODE_KEY").contains(r.get("type").asString())&&r.get("labelsOrTypes").asList(Value::asString).equals(List.of("ImportedGraphNode"))&&new HashSet<>(r.get("properties").asList(Value::asString)).equals(Set.of("namespace","id")));
        if(!found)throw new IllegalArgumentException("CSV import requires a uniqueness constraint on ImportedGraphNode(namespace,id). Enable NEO4J_IMPORT_PROVISION_SCHEMA or ask an administrator to create it.");
    }
    private long write(Transaction tx,String query,List<Map<String,Object>> rows,Counts counts,long done,long total){
        for(int start=0;start<rows.size();start+=batch){cancelled();var slice=rows.subList(start,Math.min(start+batch,rows.size()));Result result=tx.run(query,Map.of("namespace",namespace,"rows",slice));long written=result.single().get("written").asLong();counts.add(result.consume().counters());if(written!=slice.size())throw new IllegalArgumentException("CSV contains unresolved or ambiguous relationship endpoint IDs; the import was rolled back.");done+=slice.size();progress(done,total,"rows staged");}return done;
    }
    private static Map<String,List<Map<String,Object>>> groups(List<Map<String,Object>> rows){Map<String,List<Map<String,Object>>> groups=new TreeMap<>();for(var row:rows)groups.computeIfAbsent((String)row.get("type"),k->new ArrayList<>()).add(row);return groups;}
    private static void cancelled(){if(Thread.currentThread().isInterrupted())throw new CancellationException("Neo4j import cancelled.");}
    public void close(){if(driver!=null&&!supplied)driver.close();if(!supplied)driver=null;}
    private static final class Counts{long nodes,relations,nodesDeleted,relationsDeleted,properties;int statements;void add(SummaryCounters c){nodes+=c.nodesCreated();relations+=c.relationshipsCreated();nodesDeleted+=c.nodesDeleted();relationsDeleted+=c.relationshipsDeleted();properties+=c.propertiesSet();statements++;}}
}
