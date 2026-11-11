// Run explicitly as a schema administrator on Neo4j 5. No vector index is needed.
CREATE CONSTRAINT plugintell_kgentity_key IF NOT EXISTS FOR (n:KgEntity) REQUIRE n.key IS UNIQUE;
CREATE CONSTRAINT plugintell_kgfact_key IF NOT EXISTS FOR (n:KgFact) REQUIRE n.key IS UNIQUE;
CREATE CONSTRAINT plugintell_kgevidence_key IF NOT EXISTS FOR (n:KgEvidence) REQUIRE n.key IS UNIQUE;
CREATE CONSTRAINT plugintell_kgchunk_key IF NOT EXISTS FOR (n:KgChunk) REQUIRE n.key IS UNIQUE;
CREATE CONSTRAINT plugintell_kgalias_key IF NOT EXISTS FOR (n:KgAlias) REQUIRE n.key IS UNIQUE;
CREATE CONSTRAINT plugintell_kgrevision_key IF NOT EXISTS FOR (n:KgRevision) REQUIRE n.key IS UNIQUE;
CREATE INDEX plugintell_kg_entity_scope IF NOT EXISTS FOR (e:KgEntity) ON (e.scopeKey);
CREATE INDEX plugintell_kg_alias_lookup IF NOT EXISTS FOR (a:KgAlias) ON (a.scopeKey,a.normalized);
CREATE INDEX plugintell_kg_evidence_chunk IF NOT EXISTS FOR (e:KgEvidence) ON (e.chunkKey);
CREATE INDEX plugintell_kg_evidence_subject IF NOT EXISTS FOR (e:KgEvidence) ON (e.scopeKey,e.subject,e.key);
CREATE FULLTEXT INDEX plugintell_kg_entities IF NOT EXISTS FOR (e:KgEntity) ON EACH [e.scopeKey,e.name,e.aliases];
CALL db.awaitIndexes(60);
