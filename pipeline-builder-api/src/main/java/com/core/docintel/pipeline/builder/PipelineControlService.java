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
    private final TemplateCatalog templates;

    @jakarta.inject.Inject
    public PipelineControlService(PipelineStore store, GraphPipelineValidator validator, PluginCatalog catalog, ConnectionProfileService connections,TemplateCatalog templates) {
        this.store = store; this.validator = validator; this.catalog = catalog; this.connections=connections;this.templates=templates;
    }
    public PipelineControlService(PipelineStore store,GraphPipelineValidator validator,PluginCatalog catalog,ConnectionProfileService connections){this(store,validator,catalog,connections,new TemplateCatalog("./data/templates"));}
    public PipelineControlService(PipelineStore store,GraphPipelineValidator validator,PluginCatalog catalog){this(store,validator,catalog,null);}

    public List<PipelineRecord> list() { return store.list(); }
    public Optional<PipelineRecord> get(String id) { return store.get(id); }
    public PipelineRecord create(PipelineDefinition definition) {
        if(connections==null){requireValid(definition);return store.create(definition);}
        synchronized(connections){requireValid(definition);return store.create(definition);}
    }
    public PipelineRecord update(String id,long revision,PipelineDefinition definition) {
        if(connections==null){requireValid(definition);return store.update(id,revision,definition);}
        synchronized(connections){requireValid(definition);return store.update(id,revision,definition);}
    }
    public PipelineVersionRecord publish(String id,long revision) {
        if(connections==null)return publishSnapshot(id,revision);
        synchronized(connections){return publishSnapshot(id,revision);}
    }
    private PipelineVersionRecord publishSnapshot(String id,long revision){
        PipelineRecord pipeline=store.get(id).orElseThrow(()->new IllegalArgumentException("Pipeline not found: "+id));
        if(pipeline.revision()!=revision)throw new PipelineConflictException("Pipeline changed since revision "+revision+"; reload and retry.");
        PipelineDefinition snapshot=executionDefinition(pipeline.definition());
        templates.validatePin(snapshot.capabilities());
        GraphValidation result=validator.validate(snapshot,catalog.all(),true);
        if(!result.valid())throw new InvalidPipelineException(result);
        return store.publish(id,revision,snapshot);
    }
    public List<PipelineVersionRecord> versions(String id) {
        requireExists(id);
        if(connections==null)return store.versions(id);
        synchronized(connections){freezeLegacyVersions(store.versions(id));return store.versions(id);}
    }
    public Optional<PipelineVersionRecord> version(String id,int version) {
        requireExists(id);
        if(connections==null)return store.version(id,version);
        synchronized(connections){freezeLegacyVersions(store.version(id,version).stream().toList());return store.version(id,version);}
    }
    private void freezeLegacyVersions(List<PipelineVersionRecord> versions){
        versions.stream().flatMap(version->version.definition().allNodes().stream()).filter(java.util.Objects::nonNull)
                .filter(node->node.config()!=null).map(node->node.config().get("connectionRef")).filter(String.class::isInstance)
                .map(String.class::cast).distinct().forEach(connections::freezePublishedConnections);
    }
    public PipelineDefinition executionDefinition(PipelineDefinition definition){return connections==null?definition:connections.expand(definition);}
    public GraphValidation validate(PipelineDefinition definition,boolean complete){if(definition!=null)templates.validatePin(definition.capabilities());return validator.validate(executionDefinition(definition),catalog.all(),complete);}
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
