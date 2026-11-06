package com.core.docintel.pipeline.builder;

import jakarta.inject.Singleton;
import java.util.List;
import java.util.Optional;

@Singleton
public final class PipelineControlService {
    private final PipelineStore store;
    private final GraphPipelineValidator validator;
    private final PluginCatalog catalog;
    private final ConnectionProfileService connections;

    @jakarta.inject.Inject
    public PipelineControlService(PipelineStore store, GraphPipelineValidator validator, PluginCatalog catalog, ConnectionProfileService connections) {
        this.store = store; this.validator = validator; this.catalog = catalog; this.connections=connections;
    }
    public PipelineControlService(PipelineStore store,GraphPipelineValidator validator,PluginCatalog catalog){this.store=store;this.validator=validator;this.catalog=catalog;this.connections=null;}

    public List<PipelineRecord> list() { return store.list(); }
    public Optional<PipelineRecord> get(String id) { return store.get(id); }
    public PipelineRecord create(PipelineDefinition definition) { requireValid(definition); return store.create(definition); }
    public PipelineRecord update(String id,long revision,PipelineDefinition definition) { requireValid(definition); return store.update(id,revision,definition); }
    public PipelineVersionRecord publish(String id,long revision) { PipelineRecord pipeline=store.get(id).orElseThrow(()->new IllegalArgumentException("Pipeline not found: "+id)); requireValid(pipeline.definition(),true); return store.publish(id,revision); }
    public List<PipelineVersionRecord> versions(String id) { requireExists(id); return store.versions(id); }
    public Optional<PipelineVersionRecord> version(String id,int version) { requireExists(id); return store.version(id,version); }
    public PipelineDefinition executionDefinition(PipelineDefinition definition){return connections==null?definition:connections.expand(definition);}
    public GraphValidation validate(PipelineDefinition definition,boolean complete){return validator.validate(executionDefinition(definition),catalog.all(),complete);}
    public void delete(String id,long revision) { store.delete(id,revision); }
    private void requireValid(PipelineDefinition definition) { requireValid(definition,false); }
    private void requireValid(PipelineDefinition definition,boolean complete) {
        GraphValidation result=validate(definition,complete);
        if(!result.valid()) throw new InvalidPipelineException(result);
    }
    private void requireExists(String id) { if(store.get(id).isEmpty())throw new IllegalArgumentException("Pipeline not found: "+id); }

    public static final class InvalidPipelineException extends RuntimeException {
        private final GraphValidation validation;
        public InvalidPipelineException(GraphValidation validation){super(String.join("; ",validation.errors()));this.validation=validation;}
        public GraphValidation validation(){return validation;}
    }
}
