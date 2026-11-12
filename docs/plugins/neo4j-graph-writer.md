# Neo4j Graph Writer

`neo4j-graph-writer` uses `com.core.docintel.plugins.Neo4JGraphWriterPlugin` to accept an `ExtractedGraph` and return `GraphWriteResult`. Connect **document splitter → GLiNER Graph Extraction → Neo4j Graph Writer** in Studio. The port types match directly. This is separate from the existing embedding writer and evidence-validated combined extraction/writer plugin.

## Configuration

| Setting | Default | Purpose |
| --- | --- | --- |
| `NEO4J_URI` | `bolt://localhost:7687` | Bolt/Neo4j URI without embedded credentials |
| `NEO4J_USERNAME` | `neo4j` | Login name |
| `NEO4J_PASSWORD` | required | Host-resolved `secret://env/NEO4J_PASSWORD` |
| `NEO4J_DATABASE` | `neo4j` | Existing target database |
| `NEO4J_GRAPH_NAMESPACE` | `default` | Dataset partition within that database |
| `NEO4J_GRAPH_BATCH_SIZE` | `500` | Rows per UNWIND statement, 1–10000 |
| `NEO4J_GRAPH_TIMEOUT_SECONDS` | `120` | Transaction timeout and connection establishment timeout, 1–600 seconds |
| `NEO4J_GRAPH_PROVISION_SCHEMA` | `true` | Create the three required uniqueness constraints if missing |

Configuration falls back to same-named environment variables. The generated interactive CLI can request missing required settings and resolve secret references; Studio resolves references through its existing host mechanism. No password is stored in graph records, results or progress messages. The database must already exist; database creation is not performed. Namespace is a dataset partition, not an authorization mechanism: run trusted workflows with an appropriately scoped Neo4j account.

## Storage and transactions

Each write uses one managed Neo4j transaction for its document. Rows are batched within that transaction, so a failed batch rolls back the graph write. A uniquely constrained document row serializes writes to the same namespace/document. Driver-managed transient retries may re-execute the transaction; progress reports zero before writing and committed counts only after success.

- `ExtractedGraphDocument`: namespace, documentId, schemaJson, metadataJson, updatedAt and writeRevision.
- `ExtractedGraphChunk`: namespace, documentId, id, sourceName, pageNumber, ordinal and text; document connects through `HAS_CHUNK`.
- `ExtractedGraphEntity`: namespace, documentId, id, type, name, aliases, confidence, propertiesJson, mentionsJson and chunkIds. Adds a validated semantic label such as `Person`; document connects through `HAS_ENTITY`, and entity connects to source chunks through `MENTIONED_IN`.
- Semantic relationships, such as `WORKS_FOR`: id, namespace, documentId, type, confidence, chunkIds and propertiesJson. Their endpoints are scoped entity nodes.

Arbitrary properties and mentions are preserved as JSON strings to support nested structures and avoid overwriting identity fields. Semantic type names must be simple identifiers declared in the ontology; all names, IDs, text and properties are passed as parameters. Labels beginning with `ExtractedGraph` are reserved for internal storage.

This is an **upsert**, not snapshot replacement. Rerunning the same normalized graph merges its existing records; omitted entities, old relationship endpoints, old source links and changed document IDs are retained. Reusing an entity ID with a new type also retains its old semantic label. Use stable normalized IDs and a deliberate cleanup policy when replacing document versions. Entity identity is scoped to a document: this writer does not merge similarly named people across documents. It does not populate the separate `KgEntity`/assertion schema used by existing graph retrieval plugins.

The writer rejects incomplete or infeasible extraction results, duplicate IDs, undeclared entity/relationship types, missing or incorrectly typed endpoints, self-loops and unknown chunk references before accessing Neo4j. Each collection is limited to 100000 records per graph. It stores provider-supplied provenance; source offsets and factual correctness are not independently verified.

`GraphWriteResult` reports the document ID, namespace, database and counts of successfully stored entities, relationships and chunks. Counts include records updated by a repeated run, rather than only newly created records. Generated CLI output displays those counts without dumping source text.

## Schema provisioning

For Neo4j 5+, the account needs write permissions and, when provisioning is enabled, constraint creation permissions. Otherwise have an administrator run:

```cypher
CREATE CONSTRAINT extracted_graph_document IF NOT EXISTS
FOR (n:ExtractedGraphDocument) REQUIRE (n.namespace,n.documentId) IS UNIQUE;
CREATE CONSTRAINT extracted_graph_entity IF NOT EXISTS
FOR (n:ExtractedGraphEntity) REQUIRE (n.namespace,n.documentId,n.id) IS UNIQUE;
CREATE CONSTRAINT extracted_graph_chunk IF NOT EXISTS
FOR (n:ExtractedGraphChunk) REQUIRE (n.namespace,n.documentId,n.id) IS UNIQUE;
```

Then disable `NEO4J_GRAPH_PROVISION_SCHEMA`. Preflight verifies equivalent constraints by label and property set, so administrator-chosen constraint names work too. Provisioning DDL is separate from the document data transaction.

Example query (supply the desired namespace and document ID):

```cypher
MATCH (a:ExtractedGraphEntity {namespace:$namespace,documentId:$documentId})-[r]->(b:ExtractedGraphEntity)
WHERE r.namespace=$namespace AND r.documentId=$documentId
RETURN a.name, type(r), b.name, r.chunkIds;
```

Build with JDK 21: `mvn -pl plugins/neo4j-graph-writer -am package`. The artifact is `plugins/neo4j-graph-writer/target/neo4j-graph-writer-plugin.jar`. Rebuild the complete reactor and restart Studio before generating an application: the new shared `GraphWriteResult` type must also be present in its bundled `pipeline-core.jar`.

Unit tests use a driver fixture to check query parameters, batching, transaction callbacks, failure handling and validation. They do not establish connectivity to a live Neo4j instance.
