package com.plugintell.graph;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.logging.Logger;

/** Merges per-chunk extractions into a referentially consistent graph. */
final class GraphNormalizer {
    private static final Logger LOG = Logger.getLogger(GraphNormalizer.class.getName());
    private final GraphSchema schema;
    private final AliasRegistry aliases;
    private final Map<String, Node> nodes = new LinkedHashMap<>();
    private final Map<String, Edge> edges = new LinkedHashMap<>();
    private final List<String> warnings = new ArrayList<>();
    private int droppedRelationships;
    private int infeasibleChunks;
    private int receivedRelationships;
    private int chunksMissingRelationField;

    GraphNormalizer(GraphSchema schema, AliasRegistry aliases) { this.schema = schema; this.aliases = aliases; }
    int infeasibleChunks() { return infeasibleChunks; }
    int droppedRelationships() { return droppedRelationships; }
    int entityCount() { return nodes.size(); }
    int relationshipCount() { return edges.size(); }
    int receivedRelationships() { return receivedRelationships; }
    int chunksMissingRelationField() { return chunksMissingRelationField; }
    List<String> warnings() { return warnings; }

    void merge(com.core.docintel.pipeline.api.TextChunk chunk, Map<String, Object> response) {
        if (Boolean.FALSE.equals(Json.booleanValue(response.get("feasible")))) {
            infeasibleChunks++;
            warn("chunk=" + chunk.id() + ": GLiNER returned feasible=false; no reliable graph available");
            return;
        }
        Map<String, Node> byLocalId = new HashMap<>();
        Map<String, List<Node>> byTypedName = new HashMap<>();
        List<ExtractedEntity> extracted = readEntities(response);
        int index = 0;
        for (ExtractedEntity item : extracted) {
            Map<String, Object> e = item.data();
            GraphSchema.EntityType type = schema.entity(Json.firstString(e, "type", "label", "entity_type"));
            if (type == null) type = schema.entity(item.groupLabel());
            String name = Json.firstString(e, "text", "name", "value");
            if (type == null || name == null) { index++; continue; }
            String canonicalName = aliases.canonical(type.name(), name);
            if (canonicalName.isBlank()) { index++; continue; }
            String key = type.name() + "\0" + AliasRegistry.identity(canonicalName);
            String canonicalType = type.name();
            Node node = nodes.computeIfAbsent(key, k -> new Node("ent_" + hash(key), canonicalType, canonicalName));
            node.aliases.add(AliasRegistry.clean(name));
            node.chunks.add(chunk.id());
            Integer start = Json.integer(e.get("start"));
            Integer end = Json.integer(e.get("end"));
            Double confidence = confidence(e);
            node.mergeConfidence(confidence);
            node.addMention(chunk.id(), start, end, confidence);
            Object props = e.get("properties");
            if (props instanceof Map<?, ?> map) for (Map.Entry<?, ?> prop : map.entrySet()) {
                if (prop.getKey() instanceof String k && prop.getValue() != null) node.properties.putIfAbsent(k, prop.getValue());
            }
            byLocalId.put("idx:" + index, node);
            if (e.get("id") != null) byLocalId.put(e.get("id").toString(), node);
            byTypedName.computeIfAbsent(type.name() + "\0" + AliasRegistry.identity(canonicalName), unused -> new ArrayList<>()).add(node);
            index++;
        }

        for (Map<String, Object> relation : readRelations(response)) {
            GraphSchema.RelationType type = schema.relation(Json.firstString(relation, "type", "relation", "label", "name"));
            if (type == null) { drop(chunk.id(), "undeclared relationship type"); continue; }
            Node head = endpoint(relation.get("head"), type.head(), byLocalId, byTypedName);
            Node tail = endpoint(relation.get("tail"), type.tail(), byLocalId, byTypedName);
            if (head == null || tail == null) { drop(chunk.id(), "unresolved endpoint for " + type.name()); continue; }
            if (!head.type.equals(type.head()) || !tail.type.equals(type.tail())) {
                drop(chunk.id(), "endpoint type mismatch for " + type.name()); continue;
            }
            if (head.id.equals(tail.id)) { drop(chunk.id(), "self-loop for " + type.name()); continue; }
            String key = head.id + "\0" + type.name() + "\0" + tail.id;
            Edge edge = edges.computeIfAbsent(key, k -> new Edge("rel_" + hash(key), type.name(), head.id, tail.id));
            edge.chunks.add(chunk.id());
            edge.mergeConfidence(confidence(relation));
        }
    }

    private List<ExtractedEntity> readEntities(Map<String, Object> response) {
        Object raw = response.get("entities");
        if (raw == null && response.get("result") instanceof Map<?, ?> result) raw = result.get("entities");
        List<ExtractedEntity> out = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object item : list) if (item instanceof Map<?, ?> m) out.add(new ExtractedEntity(Json.object(m), null));
        } else if (raw instanceof Map<?, ?> groups) {
            for (Map.Entry<?, ?> group : groups.entrySet()) {
                if (group.getValue() instanceof List<?> list)
                    for (Object item : list) if (item instanceof Map<?, ?> m) out.add(new ExtractedEntity(Json.object(m), String.valueOf(group.getKey())));
            }
        }
        return out;
    }
    private List<Map<String, Object>> readRelations(Map<String, Object> response) {
        Object raw = response.getOrDefault("relations", response.get("relationships"));
        if (raw == null && response.get("result") instanceof Map<?, ?> result)
            raw = result.containsKey("relations") ? result.get("relations") : result.get("relationships");
        if (raw == null) chunksMissingRelationField++;
        List<Map<String, Object>> out = new ArrayList<>();
        if (raw instanceof List<?> list) for (Object item : list)
            if (item instanceof Map<?, ?> m) out.add(Json.object(m));
        receivedRelationships += out.size();
        return out;
    }
    private Node endpoint(Object ref, String expectedType, Map<String, Node> ids,
                          Map<String, List<Node>> names) {
        if (ref instanceof Number n) return ids.get("idx:" + n.intValue());
        if (ref instanceof String str) {
            Node byId = ids.get(str);
            if (byId != null) return byId;
            List<Node> matches = names.get(expectedType + "\0" + AliasRegistry.identity(aliases.canonical(expectedType, str)));
            return matches != null && !matches.isEmpty() ? matches.get(0) : null;
        }
        if (ref instanceof Map<?, ?> m) {
            Map<String, Object> obj = Json.object(m);
            for (String k : List.of("entity_idx", "index", "id", "entity_id")) {
                Object val = obj.get(k);
                Node n = endpoint(val, expectedType, ids, names);
                if (n != null) return n;
            }
            String text = Json.firstString(obj, "text", "name", "value");
            if (text != null) return endpoint(text, expectedType, ids, names);
        }
        return null;
    }
    private static Double confidence(Map<String, Object> map) {
        Object value = map.containsKey("confidence") ? map.get("confidence") : map.get("score");
        Double c = Json.decimal(value);
        return c != null && Double.isFinite(c) && c >= 0 && c <= 1 ? c : null;
    }
    private void drop(String chunkId, String reason) {
        droppedRelationships++;
        warn("chunk=" + chunkId + ": dropped relation: " + reason);
    }
    private void warn(String message) {
        LOG.warning(message);
        if (warnings.size() < 100) warnings.add(message);
    }
    private static String hash(String text) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes, 0, 12);
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    List<Map<String, Object>> entitiesJson() {
        return nodes.values().stream().sorted(Comparator.comparing(x -> x.id)).map(Node::json).toList();
    }
    List<Map<String, Object>> relationshipsJson() {
        return edges.values().stream().sorted(Comparator.comparing(x -> x.id)).map(Edge::json).toList();
    }
    private record ExtractedEntity(Map<String, Object> data, String groupLabel) {}

    private static final class Node {
        final String id, type, name;
        Double confidence;
        final Set<String> aliases = new LinkedHashSet<>(), chunks = new TreeSet<>(), mentionKeys = new HashSet<>();
        final List<Map<String, Object>> mentions = new ArrayList<>();
        final Map<String, Object> properties = new LinkedHashMap<>();
        Node(String id, String type, String name) { this.id = id; this.type = type; this.name = name; }
        void mergeConfidence(Double c) { if (c != null) confidence = confidence == null ? c : Math.max(confidence, c); }
        void addMention(String chunkId, Integer start, Integer end, Double confidence) {
            String key = chunkId + "\0" + start + "\0" + end;
            if (!mentionKeys.add(key)) return;
            Map<String, Object> mention = Json.obj("chunk_id", chunkId);
            if (start != null && start >= 0) mention.put("start", start);
            if (end != null && end >= 0) mention.put("end", end);
            if (confidence != null) mention.put("confidence", confidence);
            mentions.add(mention);
        }
        Map<String, Object> json() {
            return Json.obj("id", id, "type", type, "name", name, "aliases", aliases,
                "confidence", confidence, "properties", properties, "chunk_ids", chunks, "mentions", mentions);
        }
    }
    private static final class Edge {
        final String id, type, sourceId, targetId;
        Double confidence;
        final Set<String> chunks = new TreeSet<>();
        Edge(String id, String type, String sourceId, String targetId) {
            this.id = id; this.type = type; this.sourceId = sourceId; this.targetId = targetId;
        }
        void mergeConfidence(Double c) { if (c != null) confidence = confidence == null ? c : Math.max(confidence, c); }
        Map<String, Object> json() {
            return Json.obj("id", id, "type", type, "source_id", sourceId, "target_id", targetId,
                "confidence", confidence, "chunk_ids", chunks);
        }
    }
}
