# Standalone Neo4j CSV / Cypher import

Add **Neo4j CSV / Cypher Import** (`neo4j-file-import`) as the only node in the ingestion lane. Leave the query lane empty. Set the Neo4j connection and publish/create the project. Its contract is `File → GraphImportResult`, which satisfies both the Studio ingestion-root validation and runtime validation without a splitter, extractor or another writer. Existing typed edge validation remains in force.

Restart Studio after rebuilding to discover the plugin. Generated CLI usage:

```bat
run.bat --mode ingestion --input "D:\data\graph.csv"
run.bat --mode ingestion --input "D:\data\graph.cypher"
```

The generated CLI asks for missing required secrets and citation preference as usual. Citations do not affect this direct import. Its result is committed Neo4j counters: nodes and relationships created/deleted, properties set and data statements executed. Progress counts rows/statements staged; the final success message is printed only after commit.

## Connection and options

`NEO4J_URI=bolt://localhost:7687`, `NEO4J_USERNAME=neo4j`, and `NEO4J_DATABASE=neo4j` have defaults. `NEO4J_PASSWORD` is required and should use `secret://env/NEO4J_PASSWORD` in Studio. The target database must exist.

| Option | Default | Meaning |
| --- | --- | --- |
| `NEO4J_IMPORT_FORMAT` | `AUTO` | Detect `.csv`, `.cypher` or `.cql`; set `CSV` or `CYPHER` for other extensions |
| `NEO4J_IMPORT_NAMESPACE` | `default` | CSV identity partition; supplied to Cypher as `$namespace` |
| `NEO4J_IMPORT_BATCH_SIZE` | `500` | CSV rows per query within one transaction, maximum 10000 |
| `NEO4J_IMPORT_TIMEOUT_SECONDS` | `120` | Data transaction and connection timeout, maximum 600 seconds |
| `NEO4J_IMPORT_MAX_BYTES` | `52428800` | Input limit of 50 MiB; maximum configurable value 100 MiB |
| `NEO4J_IMPORT_PROVISION_SCHEMA` | `true` | Create the CSV node identity constraint when missing |

Files are read locally by the application as UTF-8, with optional BOM. Neo4j does not need access to the client filesystem for CSV mode. The plugin rejects malformed files before connecting. It uses the existing Neo4j driver/support dependency and closes its connection when the pipeline finishes.

## CSV format

Use this single-file graph format, also available as [graph.csv](../../plugins/neo4j-file-import/examples/graph.csv):

```csv
kind,id,label,name,source,target,type,properties
node,p1,Person,Alice,,,,"{""age"":30}"
node,o1,Organization,Acme,,,,
relationship,r1,,,p1,o1,WORKS_FOR,
```

Headers are case-sensitive. `kind` and `id` are required. Node rows require `label`; relationship rows require `source`, `target`, and `type`. Relationship rows may appear before node rows. `name` is an optional convenience property. `properties` is an optional JSON object: scalar values and homogeneous scalar arrays are supported, and nested objects/arrays are rejected. Escape JSON quotes according to CSV rules by doubling them. Quoted commas, newlines, doubled quotes and CRLF input are supported. Blank records are ignored. The maximum is 100000 data rows.

Nodes are merged as `(:ImportedGraphNode:<label> {namespace,id})`. Semantic relationships use their supplied type and `{namespace,id}` between matching endpoint nodes. Labels and relationship types must be simple identifiers, up to 64 characters. `ImportedGraphNode`, and the property keys `namespace` and `id`, are reserved. Only labels/types enter Cypher syntax after validation; IDs and property data use query parameters.

CSV node and relationship IDs must each be unique within the file. Endpoints may refer to nodes in this file or previously imported nodes in the same namespace. Missing endpoints fail the import and roll back its data transaction. Reimporting the same file updates the same nodes and relationships. Changing a relationship's endpoints or type can retain its former edge, and changing a node's label retains the previous label. Omitted records/properties are retained. No snapshot replacement or deletion is performed by CSV mode.

CSV requires a uniqueness constraint. The plugin creates it separately from the data transaction by default; an administrator can instead run this and disable provisioning:

```cypher
CREATE CONSTRAINT imported_graph_node IF NOT EXISTS
FOR (n:ImportedGraphNode) REQUIRE (n.namespace,n.id) IS UNIQUE;
```

The CSV storage model is independent of `ExtractedGraph` document-scoped storage and the evidence-validated `KgEntity` model. Namespace is an identity partition, not an authorization boundary.

## Cypher files

Supply ordinary Cypher data statements, separated by semicolons. See [graph.cypher](../../plugins/neo4j-file-import/examples/graph.cypher). The splitter preserves semicolons inside quoted strings/backtick identifiers and skips line/block comments. Single and double quoted strings support backslash escapes. The final semicolon is optional. Files are limited to 10000 statements.

All statements execute once in one explicit transaction. A failed statement rolls back the transaction; there are no implicit retries for arbitrary scripts. The sole supplied parameter is `$namespace`. Browser/cypher-shell commands such as `:begin`, `:commit`, `:param` and `:source` are rejected. Schema changes should be provisioned separately; Neo4j does not allow mixing schema and data changes within a single transaction. Statements requiring implicit transactions, such as `CALL ... IN TRANSACTIONS`, are unsupported.

Cypher files are trusted executable database instructions: their statements can modify or delete any data allowed by the configured Neo4j account. Their namespace use and repeatability depend on the script. `LOAD CSV` inside a Cypher script uses Neo4j server-side URL/file rules. Diagnostics omit file text and provider response bodies, exposing only a safe Neo4j error code and remediation guidance.

## Build and verification

Build with JDK 21: `mvn -pl plugins/neo4j-file-import -am package`. The artifact is `plugins/neo4j-file-import/target/neo4j-file-import-plugin.jar`. Rebuild Studio and its shaded runtime before exporting, because the generated application must include the shared `GraphImportResult` API record. Unit tests use driver fixtures and local files; they do not connect to a live Neo4j database.
