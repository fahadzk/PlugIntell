package com.plugintell.graph;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.logging.Logger;

/** Ontology for one document collection; entity/relationship types, not instances. */
final class GraphSchema {
    private static final Logger LOG = Logger.getLogger(GraphSchema.class.getName());
    record EntityType(String name, String description, List<String> properties) {
        Map<String, Object> json() { return Json.obj("name", name, "description", description, "properties", properties); }
        String wireName() { return name.replaceAll("([a-z0-9])([A-Z])", "$1 $2").replace('_', ' ').toLowerCase(Locale.ROOT); }
    }
    record RelationType(String name, String description, String head, String tail, List<String> properties) {
        Map<String, Object> json() { return Json.obj("name", name, "description", description, "head", head, "tail", tail, "properties", properties); }
        String wireName() { return name.toLowerCase(Locale.ROOT); }
    }

    final String domain;
    final String source;
    final List<EntityType> entities;
    final List<RelationType> relationships;
    private final Map<String, EntityType> entitiesByWire = new HashMap<>();
    private final Map<String, RelationType> relationsByWire = new HashMap<>();

    private GraphSchema(String domain, String source, List<EntityType> entities, List<RelationType> relationships) {
        this.domain = domain; this.source = source;
        this.entities = List.copyOf(entities); this.relationships = List.copyOf(relationships);
        for (EntityType e : entities) {
            entitiesByWire.put(label(e.name()), e);
            entitiesByWire.put(label(e.wireName()), e);
        }
        for (RelationType r : relationships) {
            relationsByWire.put(label(r.name()), r);
            relationsByWire.put(label(r.wireName()), r);
        }
    }

    static String label(String value) { return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", ""); }
    EntityType entity(String raw) { return entitiesByWire.get(label(raw)); }
    RelationType relation(String raw) { return relationsByWire.get(label(raw)); }

    Map<String, Object> toJson() {
        return Json.obj("domain", domain, "schema_version", "1.0", "source", source,
            "entities", entities.stream().map(EntityType::json).toList(),
            "relationships", relationships.stream().map(RelationType::json).toList());
    }

    static GraphSchema fromParts(Object domainObject, Object entityObject, Object relationObject,
                                 String source, boolean strict) {
        if (!(entityObject instanceof List<?> entityRaw) || entityRaw.isEmpty())
            throw new IllegalArgumentException("Schema must define a nonempty 'entities' array");
        if (entityRaw.size() > 20) throw new IllegalArgumentException("Schema cannot exceed 20 entity types");
        List<EntityType> entities = new ArrayList<>();
        Set<String> seenEntityTypes = new HashSet<>();
        for (Object item : entityRaw) {
            Map<String, Object> e = Json.object(item);
            String name = Json.firstString(e, "name");
            if (name == null || !name.matches("[A-Za-z][A-Za-z0-9_]{0,63}"))
                throw new IllegalArgumentException("Invalid entity type: " + name);
            if (!seenEntityTypes.add(label(name))) {
                if (strict) throw new IllegalArgumentException("Duplicate entity type: " + name);
                LOG.warning("Dropped duplicate entity type " + name); continue;
            }
            entities.add(new EntityType(name, Objects.toString(e.get("description"), ""), strings(e.get("properties"))));
        }
        if (!(relationObject instanceof List<?> relationRaw)) throw new IllegalArgumentException("Schema must define a 'relationships' array");
        if (relationRaw.size() > 30) throw new IllegalArgumentException("Schema cannot exceed 30 relationship types");
        Map<String, String> names = new HashMap<>();
        for (EntityType e : entities) names.put(label(e.name()), e.name());
        List<RelationType> relations = new ArrayList<>();
        Set<String> seenRelationTypes = new HashSet<>();
        for (Object item : relationRaw) {
            Map<String, Object> r = Json.object(item);
            String name = Json.firstString(r, "name");
            String head = names.get(label(Json.firstString(r, "head")));
            String tail = names.get(label(Json.firstString(r, "tail")));
            String rawHead = Json.firstString(r, "head");
            String rawTail = Json.firstString(r, "tail");
            if (name == null || !name.matches("[A-Za-z][A-Za-z0-9_]{0,63}") || head == null || tail == null) {
                String msg = "Invalid relationship definition: " + name + " (" + rawHead + " -> " + rawTail + ")";
                if (strict) throw new IllegalArgumentException(msg);
                LOG.warning("Dropped " + msg); continue;
            }
            if (!seenRelationTypes.add(label(name))) {
                if (strict) throw new IllegalArgumentException("Duplicate relationship type: " + name);
                LOG.warning("Dropped duplicate relationship type " + name); continue;
            }
            relations.add(new RelationType(name, Objects.toString(r.get("description"), ""), head, tail, strings(r.get("properties"))));
        }
        return new GraphSchema(domainObject instanceof String domain && !domain.isBlank() ? domain : "General", source, entities, relations);
    }
    private static List<String> strings(Object raw) {
        if (!(raw instanceof List<?> list)) return List.of();
        return list.stream().filter(x -> x instanceof String).map(x -> (String) x).toList();
    }

    static GraphSchema defaults() {
        Object entities = List.of(
            Json.obj("name", "Person", "description", "People", "properties", List.of("name")),
            Json.obj("name", "Organization", "description", "Businesses, groups and institutions", "properties", List.of("name")),
            Json.obj("name", "Location", "description", "Geographic places", "properties", List.of("name")),
            Json.obj("name", "Event", "description", "Important events", "properties", List.of("name", "date")),
            Json.obj("name", "Date", "description", "Calendar dates or years", "properties", List.of("value")),
            Json.obj("name", "Concept", "description", "Significant ideas or topics", "properties", List.of("name")));
        Object relations = List.of(
            rel("WORKS_FOR", "Person", "Organization"), rel("FOUNDED", "Person", "Organization"),
            rel("LOCATED_IN", "Organization", "Location"), rel("PARTICIPATED_IN", "Person", "Event"),
            rel("OCCURRED_IN", "Event", "Location"), rel("OCCURRED_ON", "Event", "Date"),
            rel("INVOLVED_IN", "Organization", "Event"), rel("RELATED_TO", "Concept", "Concept"));
        return fromParts("General", entities, relations, "default", true);
    }
    private static Object rel(String name, String head, String tail) {
        return Json.obj("name", name, "head", head, "tail", tail, "description", name.replace('_', ' ').toLowerCase(Locale.ROOT), "properties", List.of());
    }
}
