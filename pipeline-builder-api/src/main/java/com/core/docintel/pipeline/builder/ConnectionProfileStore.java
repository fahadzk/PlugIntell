package com.core.docintel.pipeline.builder;

import java.util.List;
import java.util.Optional;

public interface ConnectionProfileStore {
    List<ConnectionProfileRecord> list();
    Optional<ConnectionProfileRecord> get(String id);
    ConnectionProfileRecord create(String name, String pluginId, String pluginVersion, java.util.Map<String, Object> config);
    ConnectionProfileRecord createMultiProvider(String name, String primaryPluginId, String primaryPluginVersion,
                                                java.util.Map<String, ConnectionPluginSettings> pluginConfigs);
    ConnectionProfileRecord update(String id, long expectedRevision, String name, java.util.Map<String, Object> config);
    ConnectionProfileRecord updateMultiProvider(String id, long expectedRevision, String name,
                                                String primaryPluginId, String primaryPluginVersion,
                                                java.util.Map<String, ConnectionPluginSettings> pluginConfigs);
    void delete(String id, long expectedRevision);
}
