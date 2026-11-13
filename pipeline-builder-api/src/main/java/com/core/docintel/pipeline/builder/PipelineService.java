package com.core.docintel.pipeline.builder;

import jakarta.inject.Singleton;
import java.util.*;

@Singleton
public final class PipelineService {
    private final PluginCatalog catalog;
    public PipelineService(PluginCatalog catalog) { this.catalog = catalog; }
    public PipelineValidation validate(PipelineRequest request) {
        List<String> errors = new ArrayList<>(), links = new ArrayList<>();
        List<PluginInfo> selected = new ArrayList<>();
        if (request == null || request.projectName() == null || !request.projectName().matches("[A-Za-z][A-Za-z0-9_-]{0,63}"))
            errors.add("Project name must start with a letter and contain only letters, digits, '_' or '-'.");
        if (request != null && request.outputFormat() != null && !Set.of("jar", "native-image").contains(request.outputFormat()))
            errors.add("Output format must be 'jar' or 'native-image'.");
        if (request == null || request.pluginIds() == null || request.pluginIds().isEmpty()) errors.add("Select at least one plugin.");
        else {
            Map<String,PluginInfo> byId = new HashMap<>(); catalog.all().forEach(p -> byId.put(p.id(),p));
            Set<String> seen = new HashSet<>();
            for (String id : request.pluginIds()) {
                if (!seen.add(id)) { errors.add("Duplicate plugin ID: " + id); continue; }
                PluginInfo p = byId.get(id);
                if (p == null) { errors.add("Unknown plugin ID: " + id); continue; }
                selected.add(p);
                if (!"1.0".equals(p.apiVersion())) errors.add("Plugin '"+id+"' uses API version "+p.apiVersion()+"; supported version is 1.0.");
                try { catalog.verifyExecutable(p); }
                catch (IllegalArgumentException e) { errors.add(e.getMessage()); }
            }
            for (int i=1;i<selected.size();i++) {
                PluginInfo prev=selected.get(i-1), next=selected.get(i);
                if (!com.core.docintel.pipeline.api.PluginTypes.accepts(next.inputType(),prev.outputType())) errors.add("Cannot connect '"+next.id()+"': expected input "+next.inputType()+", previous plugin '"+prev.id()+"' outputs "+prev.outputType()+".");
                else links.add(prev.id()+" -> "+next.id()+" OK ("+prev.outputType()+")");
            }
        }
        boolean sourceSupported = !selected.isEmpty() && Set.of("File","SearchRequest","String").contains(selected.getFirst().inputType());
        String message = sourceSupported ? "" : "Generation currently supports pipelines starting with File, SearchRequest, or String.";
        return new PipelineValidation(errors.isEmpty(), errors.isEmpty() && sourceSupported, List.copyOf(selected), List.copyOf(errors), List.copyOf(links), message);
    }
}
