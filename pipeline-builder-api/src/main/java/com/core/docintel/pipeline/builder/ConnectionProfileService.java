package com.core.docintel.pipeline.builder;

import jakarta.inject.Singleton;
import java.util.*;

@Singleton
public final class ConnectionProfileService {
    private final ConnectionProfileStore store;
    private final PluginCatalog catalog;
    private final GraphPipelineValidator validator;
    private final PluginConnectionTestService tests;
    private final PipelineStore pipelines;

    public ConnectionProfileService(ConnectionProfileStore store, PluginCatalog catalog,
                                    GraphPipelineValidator validator, PluginConnectionTestService tests,PipelineStore pipelines) {
        this.store=store;this.catalog=catalog;this.validator=validator;this.tests=tests;this.pipelines=pipelines;
    }
    public List<ConnectionProfileRecord> list(){return store.list();}
    public Optional<ConnectionProfileRecord> get(String id){return store.get(id);}
    public ConnectionProfileRecord create(ConnectionProfileRequest request){
        String name=validName(request.name());Map<String,ConnectionPluginSettings> configs=request.pluginConfigs();
        if(configs==null||configs.isEmpty()){
            PluginInfo provider=plugin(request.pluginId());Map<String,Object> config=request.config()==null?Map.of():request.config();
            configs=Map.of(provider.id(),new ConnectionPluginSettings(provider.version(),config));
        }
        validate(configs);
        String primaryId=primary(request.pluginId(),configs);ConnectionPluginSettings primary=configs.get(primaryId);
        return store.createMultiProvider(name,primaryId,primary.pluginVersion(),configs);
    }
    public ConnectionProfileRecord update(String id,ConnectionProfileRequest request){
        ConnectionProfileRecord current=store.get(id).orElseThrow(()->new NoSuchElementException("Connection profile not found."));
        if(referenced(id))throw new ConnectionProfileInUseException("Connection profile is referenced by a pipeline draft or published version; create a new profile and update the pipeline reference instead.");
        if(request.expectedRevision()==null||request.expectedRevision()!=current.revision())throw new ConnectionProfileConflictException("Connection profile changed since revision "+request.expectedRevision()+"; reload and retry.");
        Map<String,ConnectionPluginSettings> configs=request.pluginConfigs();
        if(configs==null){
            configs=new LinkedHashMap<>(current.pluginConfigs());
            if(request.config()!=null){String providerId=request.pluginId()==null?current.pluginId():request.pluginId();PluginInfo provider=plugin(providerId);configs.put(provider.id(),new ConnectionPluginSettings(provider.version(),request.config()));}
        }
        validate(configs);
        String primaryId=primary(current.pluginId(),configs);ConnectionPluginSettings primary=configs.get(primaryId);
        return store.updateMultiProvider(id,current.revision(),validName(request.name()==null?current.name():request.name()),primaryId,primary.pluginVersion(),configs);
    }
    public void delete(String id,long revision){if(store.get(id).isEmpty())throw new NoSuchElementException("Connection profile not found.");if(referenced(id))throw new ConnectionProfileInUseException("Connection profile is referenced by a pipeline draft or published version.");store.delete(id,revision);}
    public PluginConnectionTestResult test(String id){
        ConnectionProfileRecord profile=store.get(id).orElseThrow(()->new NoSuchElementException("Connection profile not found."));
        long started=System.nanoTime();List<String> failures=new ArrayList<>();boolean tested=false;
        for(var entry:profile.pluginConfigs().entrySet()){
            PluginInfo provider=plugin(entry.getKey());ConnectionPluginSettings settings=entry.getValue();
            if(!settings.pluginVersion().equals(provider.version()))failures.add(provider.name()+": saved plugin version is outdated.");
            else{PluginConnectionTestResult result=tests.test(provider.id(),settings.config());tested|=result.connectionTested();if(!result.success())failures.add(provider.name()+": "+result.message());}
        }
        long elapsed=java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started);
        return failures.isEmpty()?new PluginConnectionTestResult(true,tested,"All "+profile.pluginConfigs().size()+" plugin(s) connection checks passed.",elapsed)
                :new PluginConnectionTestResult(false,tested,String.join("; ",failures),elapsed);
    }

    public PipelineDefinition expand(PipelineDefinition definition){
        if(definition==null||definition.nodes()==null)return definition;
        List<PipelineNode> nodes=new ArrayList<>(definition.nodes().size());
        for(PipelineNode node:definition.nodes()){
            Map<String,Object> config=node.config()==null?Map.of():node.config();Object ref=config.get("connectionRef");
            if(ref==null){nodes.add(node);continue;}
            if(!(ref instanceof String id)||id.isBlank())throw new IllegalArgumentException("Node '"+node.id()+"' connectionRef must contain a connection profile ID.");
            ConnectionProfileRecord profile=store.get(id).orElseThrow(()->new IllegalArgumentException("Node '"+node.id()+"' references an unknown connection profile."));
            PluginInfo provider=plugin(node.pluginId());ConnectionPluginSettings settings=profile.pluginConfigs().get(node.pluginId());
            if(settings==null)throw new IllegalArgumentException("Node '"+node.id()+"' connection profile does not include settings for plugin '"+node.pluginId()+"'.");
            if(!settings.pluginVersion().equals(provider.version()))throw new IllegalArgumentException("Node '"+node.id()+"' connection profile uses an outdated plugin version.");
            if(!settings.pluginVersion().equals(node.pluginVersion()))throw new IllegalArgumentException("Node '"+node.id()+"' connection profile plugin version is incompatible.");
            Map<String,Object> merged=new LinkedHashMap<>(settings.config());config.forEach((key,value)->{if(!key.equals("connectionRef"))merged.put(key,value);});
            nodes.add(new PipelineNode(node.id(),node.pluginId(),node.pluginVersion(),merged,node.executionPolicy()));
        }
        return new PipelineDefinition(definition.name(),definition.type(),definition.schemaVersion(),List.copyOf(nodes),definition.edges());
    }
    private void validate(Map<String,ConnectionPluginSettings> configs){
        if(configs==null||configs.isEmpty())throw new IllegalArgumentException("A connection profile must include settings for at least one plugin.");
        for(var entry:configs.entrySet()){
            PluginInfo provider=plugin(entry.getKey());ConnectionPluginSettings settings=entry.getValue();
            if(settings==null||settings.config()==null)throw new IllegalArgumentException("Connection profile settings for '"+entry.getKey()+"' are missing.");
            if(!provider.version().equals(settings.pluginVersion()))throw new IllegalArgumentException("Connection profile plugin version is outdated for '"+entry.getKey()+"'.");
            if(settings.config().containsKey("connectionRef"))throw new IllegalArgumentException("A connection profile cannot reference another profile.");
            GraphValidation result=validator.validatePluginConfiguration(provider.id(),provider.version(),settings.config(),catalog.all(),true);
            if(!result.valid())throw new IllegalArgumentException("Settings for "+provider.name()+": "+String.join("; ",result.errors()));
        }
    }
    private static String primary(String preferred,Map<String,ConnectionPluginSettings> configs){return preferred!=null&&configs.containsKey(preferred)?preferred:configs.keySet().stream().sorted().findFirst().orElseThrow(()->new IllegalArgumentException("A connection profile must include plugin settings."));}
    private boolean referenced(String profileId){for(PipelineRecord pipeline:pipelines.list()){if(references(pipeline.definition(),profileId))return true;for(PipelineVersionRecord version:pipelines.versions(pipeline.id()))if(references(version.definition(),profileId))return true;}return false;}
    private static boolean references(PipelineDefinition definition,String profileId){return definition.nodes()!=null&&definition.nodes().stream().anyMatch(node->profileId.equals(node.config()==null?null:node.config().get("connectionRef")));}
    private PluginInfo plugin(String id){PluginInfo plugin=catalog.all().stream().filter(value->value.id().equals(id)).findFirst().orElseThrow(()->new IllegalArgumentException("Unknown plugin provider."));if(!plugin.capabilities().contains("connection-preflight"))throw new IllegalArgumentException("Plugin does not declare the connection-preflight capability and cannot provide a reusable connection profile.");return plugin;}
    private static String validName(String name){if(name==null||name.isBlank()||name.length()>120)throw new IllegalArgumentException("Connection profile name must contain 1-120 characters.");return name.trim();}
}
