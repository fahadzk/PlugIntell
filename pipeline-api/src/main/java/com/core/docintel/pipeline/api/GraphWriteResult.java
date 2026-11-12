package com.core.docintel.pipeline.api;
/** Counts of records successfully committed, including records updated on a repeated run. */
public record GraphWriteResult(String documentId,String namespace,String database,
                               int entitiesStored,int relationshipsStored,int chunksStored) {}
