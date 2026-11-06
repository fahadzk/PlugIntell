package com.core.docintel.pipeline.builder;

import io.micronaut.serde.annotation.Serdeable;

@Serdeable
public enum PipelineType {
    DOCUMENT_INGESTION,
    QUERY_PROCESSING
}
