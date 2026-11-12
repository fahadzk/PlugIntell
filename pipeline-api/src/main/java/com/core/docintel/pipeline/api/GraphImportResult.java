package com.core.docintel.pipeline.api;
/** Committed Neo4j update counters, not the total size of the database. */
public record GraphImportResult(String fileName,String format,String database,long nodesCreated,
        long relationshipsCreated,long nodesDeleted,long relationshipsDeleted,long propertiesSet,int statementsExecuted) {}
