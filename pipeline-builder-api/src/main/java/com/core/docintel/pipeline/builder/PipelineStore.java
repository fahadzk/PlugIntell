package com.core.docintel.pipeline.builder;

import java.util.List;
import java.util.Optional;

public interface PipelineStore {
    List<PipelineRecord> list();
    Optional<PipelineRecord> get(String id);
    PipelineRecord create(PipelineDefinition definition);
    PipelineRecord update(String id, long expectedRevision, PipelineDefinition definition);
    PipelineVersionRecord publish(String id, long expectedRevision);
    PipelineVersionRecord publish(String id, long expectedRevision, PipelineDefinition snapshot);
    void freezePublishedConnections(String profileId, java.util.function.UnaryOperator<PipelineDefinition> snapshotter);
    List<PipelineVersionRecord> versions(String id);
    Optional<PipelineVersionRecord> version(String id, int version);
    void delete(String id, long expectedRevision);
}
