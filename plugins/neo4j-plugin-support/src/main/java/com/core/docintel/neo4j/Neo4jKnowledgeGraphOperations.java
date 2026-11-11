package com.core.docintel.neo4j;

import com.core.docintel.pipeline.api.*;
import com.core.docintel.pipeline.api.KnowledgeGraph.*;
import com.core.docintel.neo4j.KnowledgeGraphStore.*;
import com.core.docintel.neo4j.GraphTraversal.*;
import org.neo4j.driver.*;
import java.time.Duration;
import java.text.Normalizer;
import java.util.*;

/** Cohesive KG adapter owned by Neo4jChunkRepository; never creates or closes a driver. */
final class Neo4jKnowledgeGraphOperations {
    private final Driver driver;private final Neo4jConfig config;
    Neo4jKnowledgeGraphOperations(Driver driver,Neo4jConfig config){this.driver=driver;this.config=config;}
    private Session session(boolean read){return driver.session(SessionConfig.builder().withDatabase(config.database()).withDefaultAccessMode(read?AccessMode.READ:AccessMode.WRITE).build());}
    private static TransactionConfig deadline(Duration timeout){if(timeout==null||timeout.isNegative()||timeout.isZero()||timeout.toSeconds()>120)throw new IllegalArgumentException("Graph timeout must be positive and at most 120 seconds.");return TransactionConfig.builder().withTimeout(timeout).build();}
    static String lookup(String text){return GraphPolicy.lookupName(text);}
    static String chunkKey(PluginExecutionContext scope,TextChunk chunk){return GraphPolicy.hash(GraphPolicy.scopeKey(scope)+"\nslot\n"+chunk.documentId()+"\n"+chunk.pageNumber()+"\n"+chunk.ordinal());}
    static List<String> schemaCommands(String index){
        KnowledgeGraphStore.approvedIndex(index);
        List<String> commands=new ArrayList<>();
        for(String label:List.of("KgEntity","KgFact","KgEvidence","KgChunk","KgAlias","KgRevision"))commands.add("CREATE CONSTRAINT plugintell_"+label.toLowerCase(Locale.ROOT)+"_key IF NOT EXISTS FOR (n:"+label+") REQUIRE n.key IS UNIQUE");
        commands.add("CREATE INDEX plugintell_kg_entity_scope IF NOT EXISTS FOR (e:KgEntity) ON (e.scopeKey)");
        commands.add("CREATE INDEX plugintell_kg_alias_lookup IF NOT EXISTS FOR (a:KgAlias) ON (a.scopeKey,a.normalized)");
        commands.add("CREATE INDEX plugintell_kg_evidence_chunk IF NOT EXISTS FOR (e:KgEvidence) ON (e.chunkKey)");
        commands.add("CREATE INDEX plugintell_kg_evidence_subject IF NOT EXISTS FOR (e:KgEvidence) ON (e.scopeKey,e.subject,e.key)");
        commands.add("CREATE FULLTEXT INDEX "+index+" IF NOT EXISTS FOR (e:KgEntity) ON EACH [e.scopeKey,e.name,e.aliases]");
        return List.copyOf(commands);
    }
    void preflight(boolean provision,String index,Duration timeout){
        GraphTraversal.cancelled();KnowledgeGraphStore.approvedIndex(index);driver.verifyConnectivity();
        if(provision)try(Session session=session(false)){for(String command:schemaCommands(index))session.executeWrite(tx->{GraphTraversal.cancelled();tx.run(command).consume();return null;},deadline(timeout));}
        try(Session session=session(true)){
            session.executeRead(tx->{
                var found=tx.run("SHOW INDEXES YIELD name,state,type,labelsOrTypes,properties WHERE name=$name RETURN state,type,labelsOrTypes,properties",Map.of("name",index)).list();
                if(found.size()!=1||!found.getFirst().get("state").asString().equals("ONLINE")||!found.getFirst().get("type").asString().equals("FULLTEXT")
                    ||!found.getFirst().get("labelsOrTypes").asList(v->v.asString()).equals(List.of("KgEntity"))
                    ||!new HashSet<>(found.getFirst().get("properties").asList(v->v.asString())).equals(Set.of("scopeKey","name","aliases")))
                    throw new IllegalStateException("The host-approved KG full-text index must be ONLINE on KgEntity(scopeKey,name,aliases). Provision graph schema explicitly before use.");
                // Cypher 5 and Cypher 25 use different names for node uniqueness.
                var constraints=tx.run("SHOW CONSTRAINTS YIELD labelsOrTypes,properties,type,entityType RETURN labelsOrTypes,properties,type,entityType").list();
                List<String> missing=new ArrayList<>();
                for(String label:List.of("KgEntity","KgFact","KgEvidence","KgChunk","KgAlias","KgRevision")){
                    if(constraints.stream().noneMatch(r->r.get("entityType").asString().equals("NODE")
                        &&Set.of("UNIQUENESS","NODE_PROPERTY_UNIQUENESS","NODE_KEY").contains(r.get("type").asString())
                        &&r.get("labelsOrTypes").asList(v->v.asString()).equals(List.of(label))
                        &&r.get("properties").asList(v->v.asString()).equals(List.of("key"))))missing.add(label+".key");
                }
                if(!missing.isEmpty())throw new IllegalStateException("Required KG uniqueness constraints are missing: "+String.join(", ",missing)
                    +". Run examples/knowledge-graph/schema.cypher against the configured NEO4J_DATABASE as a schema administrator, or enable KG_PROVISION_SCHEMA=true on the extraction node. Publish and rebuild generated projects after configuration changes. Retrieval never provisions schema.");
                Map<String,List<String>> requiredIndexes=Map.of("plugintell_kg_entity_scope",List.of("scopeKey"),"plugintell_kg_alias_lookup",List.of("scopeKey","normalized"),"plugintell_kg_evidence_chunk",List.of("chunkKey"),"plugintell_kg_evidence_subject",List.of("scopeKey","subject","key"));
                Map<String,String> labels=Map.of("plugintell_kg_entity_scope","KgEntity","plugintell_kg_alias_lookup","KgAlias","plugintell_kg_evidence_chunk","KgEvidence","plugintell_kg_evidence_subject","KgEvidence");
                var indexes=tx.run("SHOW INDEXES YIELD name,state,type,entityType,labelsOrTypes,properties WHERE name IN $names OR labelsOrTypes IN $labels RETURN name,state,type,entityType,labelsOrTypes,properties",Map.of("names",new ArrayList<>(requiredIndexes.keySet()),"labels",List.of(List.of("KgEntity"),List.of("KgAlias"),List.of("KgEvidence")))).list();
                List<String> unavailable=new ArrayList<>();
                for(var required:requiredIndexes.entrySet()){
                    var equivalent=indexes.stream().filter(r->r.get("entityType").asString().equals("NODE")&&r.get("type").asString().equals("RANGE")
                        &&r.get("labelsOrTypes").asList(v->v.asString()).equals(List.of(labels.get(required.getKey())))
                        &&r.get("properties").asList(v->v.asString()).equals(required.getValue())).toList();
                    if(equivalent.stream().anyMatch(r->r.get("state").asString().equals("ONLINE")))continue;
                    String state=equivalent.isEmpty()?(indexes.stream().anyMatch(r->r.get("name").asString().equals(required.getKey()))?"WRONG_DEFINITION":"MISSING")
                        :String.join("/",equivalent.stream().map(r->r.get("state").asString()).distinct().sorted().toList());
                    unavailable.add(required.getKey()+"="+state);
                }
                if(!unavailable.isEmpty())throw new IllegalStateException("Required KG lookup indexes are unavailable: "+String.join(", ",unavailable)
                    +". Run examples/knowledge-graph/schema.cypher in the configured NEO4J_DATABASE as a schema administrator, then CALL db.awaitIndexes(60) and retry. Alternatively enable KG_PROVISION_SCHEMA=true on the extraction node, publish and rebuild the generated project. Equivalent ONLINE node RANGE indexes are accepted regardless of name; wrong definitions require administrator review.");
                return null;
            },deadline(timeout));
        }
    }
    WriteStatistics replace(Extraction extraction,PluginExecutionContext context,Duration timeout){
        GraphTraversal.cancelled();String scope=GraphPolicy.scopeKey(context),chunkKey=chunkKey(context,extraction.chunk());
        // Run identity is provenance, not content identity; a replay does not create new assertions.
        String revision=GraphPolicy.hash(GraphPolicy.encode(List.of(extraction.chunk(),extraction.entities(),extraction.facts(),extraction.provenance())));
        try(Session session=session(false)){
            return session.executeWrite(tx->{
                GraphTraversal.cancelled();long graphRevision=tx.run("MERGE (r:KgRevision {key:$scope}) ON CREATE SET r.value=0 SET r.value=r.value RETURN r.value AS value",Map.of("scope",scope)).single().get("value").asLong();
                var existing=tx.run("MATCH (c:KgChunk {key:$key}) RETURN c.revision AS revision,c.documentId AS documentId",Map.of("key",chunkKey)).list();
                if(!existing.isEmpty()){
                    if(!existing.getFirst().get("documentId").asString().equals(extraction.chunk().documentId()))throw new IllegalArgumentException("A stable chunk ID cannot be reassigned to another document.");
                    if(existing.getFirst().get("revision").asString().equals(revision))return new WriteStatistics(extraction.entities().size(),extraction.facts().size(),0,0,0,graphRevision,true);
                }
                var c=extraction.chunk();Map<String,Object> cp=new HashMap<>();cp.put("key",chunkKey);cp.put("scope",scope);cp.put("namespace",context.namespace());cp.put("accessScope",context.accessScope());cp.put("originalId",c.id());cp.put("documentId",c.documentId());cp.put("sourceName",c.sourceName());cp.put("page",c.pageNumber());cp.put("ordinal",c.ordinal());cp.put("text",c.text());cp.put("hash",GraphPolicy.hash(c.text()));cp.put("revision",revision);
                tx.run("MERGE (c:KgChunk {key:$key}) SET c.scopeKey=$scope,c.namespace=$namespace,c.accessScope=$accessScope,c.originalId=$originalId,c.documentId=$documentId,c.sourceName=$sourceName,c.page=$page,c.ordinal=$ordinal,c.text=$text,c.contentHash=$hash,c.revision=$revision",cp).consume();
                var old=tx.run("MATCH (e:KgEvidence {chunkKey:$key,scopeKey:$scope}) RETURN e.subject AS subject LIMIT 1025",Map.of("key",chunkKey,"scope",scope)).list();if(old.size()>1024)throw new IllegalStateException("Stored chunk assertion count exceeds the supported replacement budget.");
                List<String> oldSubjects=old.stream().map(r->r.get("subject").asString()).distinct().toList();
                tx.run("MATCH (e:KgEvidence {chunkKey:$key,scopeKey:$scope}) DETACH DELETE e",Map.of("key",chunkKey,"scope",scope)).consume();
                long entitiesCreated=0,factsCreated=0,evidenceCreated=0;
                for(Entity entity:extraction.entities()){
                    GraphTraversal.cancelled();Map<String,Object> p=Map.of("key",entity.key(),"scope",scope,"type",entity.type(),"name",entity.name());
                    entitiesCreated+=tx.run("MERGE (e:KgEntity {key:$key}) ON CREATE SET e.scopeKey=$scope,e.type=$type,e.name=$name,e.aliases=[]",p).consume().counters().nodesCreated();
                    evidenceCreated+=support(tx,context,extraction,chunkKey,revision,entity.key(),"ENTITY",entity.evidence(),entity.properties(),entity.name(),entity.aliases());
                }
                for(Fact fact:extraction.facts()){
                    GraphTraversal.cancelled();factsCreated+=tx.run("MATCH (s:KgEntity {key:$source,scopeKey:$scope}),(t:KgEntity {key:$target,scopeKey:$scope}) MERGE (f:KgFact {key:$key}) ON CREATE SET f.scopeKey=$scope,f.type=$type MERGE (f)-[:KG_SOURCE]->(s) MERGE (f)-[:KG_TARGET]->(t)",Map.of("scope",scope,"source",fact.source(),"target",fact.target(),"key",fact.key(),"type",fact.type())).consume().counters().nodesCreated();
                    evidenceCreated+=support(tx,context,extraction,chunkKey,revision,fact.key(),"FACT",fact.evidence(),fact.properties(),"",List.of());
                }
                tx.run("UNWIND $subjects AS key MATCH (f:KgFact {key:key,scopeKey:$scope}) WHERE NOT EXISTS { MATCH (:KgEvidence {scopeKey:$scope})-[:KG_ASSERTS]->(f) } DETACH DELETE f",Map.of("scope",scope,"subjects",oldSubjects)).consume();
                tx.run("UNWIND $subjects AS key MATCH (e:KgEntity {key:key,scopeKey:$scope}) WHERE NOT EXISTS { MATCH (:KgEvidence {scopeKey:$scope})-[:KG_ASSERTS]->(e) } AND NOT EXISTS { MATCH (:KgFact)-[:KG_SOURCE|KG_TARGET]->(e) } DETACH DELETE e",Map.of("scope",scope,"subjects",oldSubjects)).consume();
                Set<String> affected=new LinkedHashSet<>(oldSubjects);extraction.entities().forEach(e->affected.add(e.key()));
                // Rebuild searchable names only from surviving assertions: removed evidence cannot leave active aliases.
                var aliasCounts=tx.run("UNWIND $subjects AS key MATCH (n:KgEntity {key:key,scopeKey:$scope}) "
                    +"CALL { WITH n MATCH (ev:KgEvidence {scopeKey:$scope,subject:n.key,kind:'ENTITY'}) WITH ev WHERE ev.key IS NOT NULL RETURN ev.entityName AS name ORDER BY ev.key LIMIT 1 } "
                    +"CALL { WITH n MATCH (ev:KgEvidence {scopeKey:$scope,subject:n.key,kind:'ENTITY'}) UNWIND ev.lookupNames AS alias WITH DISTINCT alias ORDER BY alias LIMIT 1025 RETURN collect(alias) AS names } "
                    +"OPTIONAL MATCH (:KgAlias {scopeKey:$scope})-[old:KG_ALIAS_OF]->(n) WITH n,name,names,collect(old) AS prior "
                    +"FOREACH (r IN prior | DELETE r) SET n.name=name,n.aliases=names "
                    +"FOREACH (alias IN names | MERGE (a:KgAlias {key:$scope+'|'+alias}) ON CREATE SET a.scopeKey=$scope,a.normalized=alias MERGE (a)-[:KG_ALIAS_OF]->(n)) RETURN size(names) AS count",Map.of("scope",scope,"subjects",new ArrayList<>(affected))).list();
                if(aliasCounts.stream().anyMatch(r->r.get("count").asInt()>1024))throw new IllegalArgumentException("Aggregate active alias limit exceeded; chunk transaction rolled back.");
                // Legacy TextChunk nodes are never modified or used to authorize graph evidence.
                tx.run("MATCH (c:KgChunk {key:$key,scopeKey:$scope})-[r:KG_ORIGIN]->() DELETE r",Map.of("key",chunkKey,"scope",scope)).consume();
                tx.run("MATCH (c:KgChunk {key:$key}),(legacy:TextChunk {id:$id,documentId:$document}) MERGE (c)-[:KG_ORIGIN]->(legacy)",Map.of("key",chunkKey,"id",c.id(),"document",c.documentId())).consume();
                GraphTraversal.cancelled();graphRevision=tx.run("MATCH (r:KgRevision {key:$scope}) SET r.value=r.value+1 RETURN r.value AS value",Map.of("scope",scope)).single().get("value").asLong();
                return new WriteStatistics(extraction.entities().size(),extraction.facts().size(),entitiesCreated,factsCreated,evidenceCreated,graphRevision,false);
            },deadline(timeout));
        }
    }
    private long support(TransactionContext tx,PluginExecutionContext context,Extraction extraction,String chunk,String revision,String subject,String kind,Evidence quote,Map<String,Object> properties,String entityName,List<String> entityAliases){
        Map<String,Object> p=new HashMap<>();p.put("key",GraphPolicy.hash(chunk+"\n"+revision+"\n"+subject));p.put("chunk",chunk);p.put("revision",revision);p.put("subject",subject);p.put("kind",kind);p.put("scope",GraphPolicy.scopeKey(context));p.put("quote",quote.quote());p.put("start",quote.start());p.put("end",quote.end());p.put("properties",GraphPolicy.encode(properties));
        p.put("entityName",entityName);p.put("entityAliases",entityAliases);LinkedHashSet<String> lookupNames=new LinkedHashSet<>();if(!entityName.isBlank())lookupNames.add(lookup(entityName));entityAliases.forEach(alias->lookupNames.add(lookup(alias)));p.put("lookupNames",new ArrayList<>(lookupNames));
        Map<String,Object> provenance=new TreeMap<>(extraction.provenance());provenance.put("namespace",context.namespace());provenance.put("accessScope",context.accessScope());
        if(context.runId()!=null)provenance.put("runId",context.runId());if(context.pipelineId()!=null)provenance.put("pipelineId",context.pipelineId());if(context.pipelineVersion()!=null)provenance.put("pipelineVersion",context.pipelineVersion());if(context.nodeId()!=null)provenance.put("nodeId",context.nodeId());p.put("provenance",GraphPolicy.encode(provenance));
        // Structural labels come only from this application-owned two-way enum.
        String label=kind.equals("ENTITY")?"KgEntity":"KgFact";
        return tx.run("MATCH (c:KgChunk {key:$chunk,scopeKey:$scope}),(subject:"+label+" {key:$subject,scopeKey:$scope}) MERGE (e:KgEvidence {key:$key}) SET e.scopeKey=$scope,e.chunkKey=$chunk,e.revision=$revision,e.subject=$subject,e.kind=$kind,e.quote=$quote,e.start=$start,e.end=$end,e.propertiesJson=$properties,e.provenanceJson=$provenance,e.entityName=$entityName,e.entityAliases=$entityAliases,e.lookupNames=$lookupNames MERGE (c)-[:KG_SUPPORTS]->(e) MERGE (e)-[:KG_ASSERTS]->(subject)",p).consume().counters().nodesCreated();
    }
    Schema schema(PluginExecutionContext context,int scan,Duration timeout){
        if(scan<1||scan>2000)throw new IllegalArgumentException("Graph schema scan limit must be 1-2000.");String scope=GraphPolicy.scopeKey(context);
        try(Session session=session(true)){return session.executeRead(tx->{
            Set<String> entities=new TreeSet<>(),facts=new TreeSet<>();
            var e=tx.run("MATCH (n:KgEntity {scopeKey:$scope}) WITH n LIMIT $scan RETURN n.type AS type,"+supported("n")+" AS active",Map.of("scope",scope,"scan",scan)).list();e.stream().filter(r->r.get("active").asBoolean()).forEach(r->entities.add(r.get("type").asString()));
            var f=tx.run("MATCH (n:KgFact {scopeKey:$scope}) WITH n LIMIT $scan RETURN n.type AS type,"+supported("n")+" AS active",Map.of("scope",scope,"scan",scan)).list();f.stream().filter(r->r.get("active").asBoolean()).forEach(r->facts.add(r.get("type").asString()));
            return new Schema(entities,facts,e.size()>=scan||f.size()>=scan);
        },deadline(timeout));}
    }
    private static String supported(String subject){return "EXISTS { MATCH (e:KgEvidence {scopeKey:$scope,subject:"+subject+".key})-[:KG_ASSERTS]->("+subject+"),(c:KgChunk {scopeKey:$scope})-[:KG_SUPPORTS]->(e) WHERE e.key IS NOT NULL AND c.revision=e.revision }";}
    RetrievalResult retrieve(SearchRequest request,Plan plan,PluginExecutionContext context,Limits limits,List<String> warnings){
        if(plan.hops()<0||plan.hops()>3||!Set.of("OUT","IN","BOTH").contains(plan.direction()))throw new IllegalArgumentException("Invalid bounded graph plan.");
        long ends=System.nanoTime()+limits.timeout().toNanos();
        try(Session session=session(true)){return session.executeRead(tx->{if(System.nanoTime()>=ends)throw new IllegalStateException("Graph read deadline exceeded.");return new GraphTraversal(new ReaderImpl(tx,GraphPolicy.scopeKey(context),limits.fulltextIndex(),ends),limits).search(request,plan,warnings);},deadline(limits.timeout()));}
    }
    private static final class ReaderImpl implements GraphTraversal.Reader {
        private final TransactionContext tx;private final String scope,index;private final long ends;private boolean seedCapped;
        ReaderImpl(TransactionContext tx,String scope,String index,long ends){this.tx=tx;this.scope=scope;this.index=index;this.ends=ends;}
        private void check(){GraphTraversal.cancelled();if(System.nanoTime()>=ends)throw new IllegalStateException("Graph read deadline exceeded.");}
        public boolean seedCapped(){return seedCapped;}
        public long revision(){check();var rows=tx.run("MATCH (r:KgRevision {key:$scope}) RETURN r.value AS value",Map.of("scope",scope)).list();return rows.isEmpty()?0:rows.getFirst().get("value").asLong();}
        public List<Node> exact(List<String> names,int limit){
            check();List<String> terms=names.stream().map(Neo4jKnowledgeGraphOperations::lookup).distinct().limit(16).toList();
            var rows=tx.run("UNWIND $names AS term MATCH (a:KgAlias {scopeKey:$scope,normalized:term})-[:KG_ALIAS_OF]->(n:KgEntity) WHERE n.scopeKey=$scope WITH DISTINCT n LIMIT $limit RETURN n.key AS key,n.type AS type,n.name AS name,n.aliases[..32] AS aliases,"+supported("n")+" AS active ORDER BY key",Map.of("scope",scope,"names",terms,"limit",limit)).list();seedCapped|=rows.size()>=limit;return rows.stream().filter(r->r.get("active").asBoolean()).map(ReaderImpl::node).toList();
        }
        public List<Node> lexical(String text,int limit){
            check();List<String> terms=Arrays.stream(text.split("[^\\p{L}\\p{N}]+" )).filter(s->!s.isBlank()).limit(16).toList();if(terms.isEmpty())return List.of();
            String words=String.join(" OR ",terms.stream().map(t->"\""+t+"\"").toList());String query="scopeKey:"+scope+" AND (name:("+words+") OR aliases:("+words+"))";
            var rows=tx.run("CALL db.index.fulltext.queryNodes($index,$query,{limit:$limit}) YIELD node AS n,score WHERE n.scopeKey=$scope RETURN n.key AS key,n.type AS type,n.name AS name,n.aliases[..32] AS aliases,"+supported("n")+" AS active ORDER BY score DESC,key",Map.of("scope",scope,"index",index,"query",query,"limit",limit)).list();seedCapped|=rows.size()>=limit;return rows.stream().filter(r->r.get("active").asBoolean()).map(ReaderImpl::node).toList();
        }
        private static Node node(org.neo4j.driver.Record r){return new Node(r.get("key").asString(),r.get("type").asString(),r.get("name").asString(),r.get("aliases").asList(v->v.asString()));}
        public Neighbors neighbors(String key,String direction,int limit,Set<String> entityTypes,Set<String> relationTypes){
            check();List<String> ids=new ArrayList<>();boolean capped=false;
            List<String> directions=direction.equals("BOTH")?List.of("KG_SOURCE","KG_TARGET"):List.of(direction.equals("OUT")?"KG_SOURCE":"KG_TARGET");
            for(String physical:directions){int count=direction.equals("BOTH")?Math.max(1,limit/2):limit;if(ids.size()+count>limit)count=limit-ids.size();if(count<=0)break;
                var rows=tx.run("MATCH (n:KgEntity {key:$key,scopeKey:$scope})<-[:"+physical+"]-(f:KgFact) RETURN f.key AS key LIMIT $limit",Map.of("key",key,"scope",scope,"limit",count)).list();rows.forEach(r->ids.add(r.get("key").asString()));capped|=rows.size()>=count;
            }
            if(ids.isEmpty())return new Neighbors(List.of(),0,false);
            List<Edge> result=tx.run("UNWIND $ids AS id MATCH (f:KgFact {key:id,scopeKey:$scope})-[:KG_SOURCE]->(s:KgEntity {scopeKey:$scope}),(f)-[:KG_TARGET]->(t:KgEntity {scopeKey:$scope}) WHERE (size($entityTypes)=0 OR (s.type IN $entityTypes AND t.type IN $entityTypes)) AND (size($relationTypes)=0 OR f.type IN $relationTypes) AND "+supported("s")+" AND "+supported("t")+" AND "+supported("f")+" RETURN f.key AS key,f.type AS type,s.key AS source,t.key AS target,s.type AS sourceType,s.name AS sourceName,s.aliases[..32] AS sourceAliases,t.type AS targetType,t.name AS targetName,t.aliases[..32] AS targetAliases",Map.of("scope",scope,"ids",ids,"entityTypes",new ArrayList<>(entityTypes),"relationTypes",new ArrayList<>(relationTypes))).list(r->new Edge(r.get("key").asString(),r.get("source").asString(),r.get("type").asString(),r.get("target").asString(),new Node(r.get("source").asString(),r.get("sourceType").asString(),r.get("sourceName").asString(),r.get("sourceAliases").asList(v->v.asString())),new Node(r.get("target").asString(),r.get("targetType").asString(),r.get("targetName").asString(),r.get("targetAliases").asList(v->v.asString()))));
            return new Neighbors(result,ids.size(),capped);
        }
        public List<Support> evidence(String subject,int limit){
            check();return tx.run("MATCH (e:KgEvidence {scopeKey:$scope,subject:$subject}) WHERE e.key IS NOT NULL WITH e ORDER BY e.key LIMIT $limit MATCH (c:KgChunk {scopeKey:$scope})-[:KG_SUPPORTS]->(e),(e)-[:KG_ASSERTS]->(asserted {key:$subject,scopeKey:$scope}) WHERE c.revision=e.revision RETURN e.key AS id,e.subject AS subject,e.kind AS kind,c.key AS chunkId,c.originalId AS originalChunkId,c.documentId AS documentId,c.sourceName AS sourceName,c.page AS page,c.text AS text,e.quote AS quote,e.start AS start,e.end AS end,e.propertiesJson AS properties,e.provenanceJson AS provenance,e.entityName AS entityName,e.entityAliases AS entityAliases",Map.of("scope",scope,"subject",subject,"limit",limit)).list(r->{Map<String,Object> provenance=new TreeMap<>(GraphPolicy.JSON.convertValue(GraphPolicy.parse(r.get("provenance").asString()),Map.class));provenance.put("originalChunkId",r.get("originalChunkId").asString());if(!r.get("entityName").isNull()&&!r.get("entityName").asString().isBlank()){provenance.put("entityName",r.get("entityName").asString());provenance.put("entityAliases",r.get("entityAliases").asList(v->v.asString()));}return new Support(r.get("id").asString(),r.get("subject").asString(),r.get("kind").asString(),r.get("chunkId").asString(),r.get("documentId").asString(),r.get("sourceName").asString(),r.get("page").asInt(),r.get("text").asString(),r.get("quote").asString(),r.get("start").asInt(),r.get("end").asInt(),r.get("properties").asString(),provenance);});
        }
    }
}

