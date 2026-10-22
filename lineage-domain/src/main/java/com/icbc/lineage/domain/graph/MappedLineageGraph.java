package com.icbc.lineage.domain.graph;

import com.icbc.lineage.domain.catalog.NameDictionary;
import com.icbc.lineage.domain.catalog.TableNameMapper;
import com.icbc.lineage.domain.model.LineageIssue;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Read-only business-table projection of a raw graph. Technical identifiers and
 * evidence remain available through aliases and original_* fields.
 */
public final class MappedLineageGraph {

    private static final String TABLE_PREFIX = "table:";
    private static final String COLUMN_PREFIX = "column:";

    private final LineageGraph original;
    private final LineageGraph graph;
    private final TableNameMapper mapper;
    private final NameDictionary dictionary;
    private final Map<String, String> aliases;
    private final Map<String, Map<String, Object>> nodeMetadata;
    private final List<Map<String, Object>> pendingFields;
    private final Map<String, Object> mappingSummary;
    private final List<LineageIssue> issues;

    public MappedLineageGraph(LineageGraph original, TableNameMapper mapper, NameDictionary dictionary) {
        this(original, mapper, dictionary, ProjectionPlan.build(original, mapper));
    }

    private MappedLineageGraph(LineageGraph original, TableNameMapper mapper,
                               NameDictionary dictionary, ProjectionPlan plan) {
        this.original = original;
        this.mapper = mapper;
        this.dictionary = dictionary;
        this.aliases = Map.copyOf(plan.aliases);
        this.pendingFields = plan.pendingFields.stream().map(LinkedHashMap::new)
                .<Map<String, Object>>map(Map::copyOf).toList();
        this.graph = new LineageGraph(original.sourceDocuments(), original.initialIssues(),
                original.valueLineageDecider(), plan::project, true);
        this.nodeMetadata = buildNodeMetadata(plan);
        this.mappingSummary = buildSummary(plan);
        List<LineageIssue> projectedIssues = new ArrayList<>(graph.issues());
        for (Map<String, Object> pending : pendingFields) {
            projectedIssues.add(LineageIssue.of("MAPPING_FIELD_PENDING", text(pending.get("message")), null,
                            text(pending.get("node_id")), LineageIssue.Severity.INFO)
                    .with("source", pending.get("source")).with("target", pending.get("target"))
                    .with("reason", pending.get("reason")));
        }
        this.issues = List.copyOf(projectedIssues);
    }

    public String resolve(String nodeId) {
        String resolved = aliases.getOrDefault(nodeId, nodeId);
        return graph.resolve(resolved);
    }

    public Map<String, Object> node(String nodeId) {
        String resolved = resolve(nodeId);
        Map<String, Object> result = new LinkedHashMap<>(graph.node(resolved));
        result.putAll(nodeMetadata.getOrDefault(resolved, Map.of()));
        if (!"constant".equals(result.get("type"))) {
            String parent = "table".equals(result.get("type")) ? resolved : text(result.get("parent_id"));
            String displayTable = parent.startsWith(TABLE_PREFIX) ? parent.substring(TABLE_PREFIX.length()) : "";
            result.put("original_table", text(result.get("original_qualified_name")).split("::", 2)[0]);
            result.put("display_table", displayTable);
            result.put("display_qualified_name", "column".equals(result.get("type"))
                    ? displayTable + "::" + result.get("name") : displayTable);
            long pending = pendingFields.stream().filter(item -> displayTable.equals(item.get("business_table"))).count();
            result.put("pending_field_count", "table".equals(result.get("type")) ? pending : 0L);
        }
        result.put("lineage_view", "mapped");
        return dictionary.decorate(result);
    }

    public Map<String, Object> stats() {
        Map<String, Object> result = new LinkedHashMap<>(graph.stats());
        result.put("lineage_view", "mapped");
        result.put("mapping_connectivity", mappingSummary);
        Map<String, Object> raw = original.stats();
        result.put("original_counts", result("entities", raw.get("entities"), "tables", raw.get("tables"),
                "columns", raw.get("columns"), "relations", raw.get("relations"),
                "derivations", raw.get("derivations")));
        return result;
    }

    public Map<String, Object> search(String query, String kind, String namespace, String status,
                                      String parentId, int offset, int limit, String line,
                                      Set<String> visibleNodes) {
        String resolvedParent = parentId == null || parentId.isBlank() ? "" : resolve(parentId);
        Set<String> resolvedVisible = null;
        if (visibleNodes != null) {
            resolvedVisible = new LinkedHashSet<>();
            for (String node : visibleNodes) {
                resolvedVisible.add(aliases.getOrDefault(node, node));
            }
        }
        Map<String, Object> base = graph.search("", kind, namespace, status, resolvedParent,
                0, Math.max(1, graph.nodeIds().size()), line, resolvedVisible);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> baseItems = (List<Map<String, Object>>) base.get("items");
        String needle = text(query).trim().toUpperCase(Locale.ROOT);
        List<Map<String, Object>> matched = baseItems.stream().map(item -> node(text(item.get("id"))))
                .filter(item -> needle.isEmpty() || matches(item, needle))
                .sorted(Comparator.comparing(item -> text(item.get("qualified_name"))))
                .toList();
        return result("total", matched.size(), "offset", offset,
                "items", matched.stream().skip(Math.max(0, offset)).limit(Math.max(0, limit)).toList());
    }

    public Map<String, Object> subgraph(String root, String direction, int depth, int maxNodes,
                                        boolean includeConstants, int maxEdges,
                                        LineageGraph.EdgePredicate edgeAllowed) {
        return mappedResult(graph.subgraph(resolve(root), direction, depth, maxNodes,
                includeConstants, maxEdges, edgeAllowed));
    }

    public Map<String, Object> impact(String root, int depth, int maxNodes) {
        return mappedResult(graph.impact(resolve(root), depth, maxNodes));
    }

    public Map<String, Object> associatedTables(String root, int maxNodes) {
        return mappedResult(graph.associatedTables(resolve(root), maxNodes));
    }

    public Map<String, Object> tablePath(String root, String target, String direction, int maxNodes) {
        return mappedResult(graph.tablePath(resolve(root), resolve(target), direction, maxNodes));
    }

    public Map<String, Object> evidence(String root, String source, String target,
                                        int offset, int limit, String query) {
        Map<String, Object> result = new LinkedHashMap<>(graph.evidence(resolve(root),
                optionalResolve(source), optionalResolve(target), offset, limit, query));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sourceItems = (List<Map<String, Object>>) result.get("items");
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> sourceItem : sourceItems) {
            Map<String, Object> item = new LinkedHashMap<>(sourceItem);
            item.put("mapping_basis", mappingBasis(text(item.get("original_source")),
                    text(item.get("original_target"))));
            item.put("source_mapping_pending", isPending(text(item.get("source"))));
            item.put("target_mapping_pending", isPending(text(item.get("target"))));
            items.add(item);
        }
        result.put("items", items);
        result.put("lineage_view", "mapped");
        return result;
    }

    public Map<String, Object> definitions(String root, int offset, int limit, String query) {
        Map<String, Object> result = new LinkedHashMap<>(graph.definitions(resolve(root), offset, limit, query));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sourceItems = (List<Map<String, Object>>) result.get("items");
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> sourceItem : sourceItems) {
            Map<String, Object> item = new LinkedHashMap<>(sourceItem);
            item.put("mapping_basis", mappingBasis(text(item.get("original_target"))));
            items.add(item);
        }
        result.put("items", items);
        result.put("lineage_view", "mapped");
        return result;
    }

    public Map<String, Object> mappingStatus(String query, int offset, int limit, Set<String> allowedNodes) {
        String needle = text(query).trim().toUpperCase(Locale.ROOT);
        List<Map<String, Object>> rows = pendingFields.stream()
                .filter(item -> allowedNodes == null || allowedNodes.contains(item.get("node_id")))
                .filter(item -> needle.isEmpty() || text(item.get("source")).contains(needle)
                        || text(item.get("target")).contains(needle))
                .sorted(Comparator.comparing(item -> text(item.get("target")) + "\0" + text(item.get("source"))))
                .toList();
        return result("summary", allowedNodes == null ? mappingSummary : Map.of(),
                "total", rows.size(), "offset", offset,
                "items", rows.stream().skip(Math.max(0, offset)).limit(Math.max(0, limit)).toList());
    }

    public Map<String, Object> mappingSummary() {
        return mappingSummary;
    }

    public List<LineageIssue> issues() {
        return issues;
    }

    public BusinessIndex business() {
        return graph.business();
    }

    public LineageGraph projectedGraph() {
        return graph;
    }

    public Map<String, Object> decorateResult(Map<String, Object> source) {
        return mappedResult(source);
    }

    private Map<String, Object> mappedResult(Map<String, Object> source) {
        Map<String, Object> result = new LinkedHashMap<>(source);
        Object nodeRows = result.get("nodes");
        if (nodeRows instanceof Collection<?> rows) {
            List<Map<String, Object>> nodes = new ArrayList<>();
            for (Object row : rows) {
                if (row instanceof Map<?, ?> map) {
                    Map<String, Object> decorated = node(text(map.get("id")));
                    decorated.put("distance", map.get("distance"));
                    decorated.put("predecessor", map.get("predecessor"));
                    nodes.add(decorated);
                }
            }
            result.put("nodes", nodes);
        }
        Object edgeRows = result.get("edges");
        if (edgeRows instanceof Collection<?> rows) {
            String level = text(result.get("level"));
            List<Map<String, Object>> edges = new ArrayList<>();
            for (Object row : rows) {
                if (row instanceof Map<?, ?> map) {
                    Map<String, Object> edge = copy(map);
                    List<Integer> indexes = graph.edgeRelationIndexes(level,
                            text(edge.get("source")), text(edge.get("target")));
                    long mapped = indexes.stream().map(graph.relationRecords()::get)
                            .filter(item -> Boolean.TRUE.equals(item.get("mapping_applied"))).count();
                    Set<String> lines = new TreeSet<>();
                    indexes.stream().map(graph.relationRecords()::get)
                            .map(item -> text(item.get("business_line"))).filter(value -> !value.isBlank())
                            .forEach(lines::add);
                    edge.put("mapping_evidence_count", mapped);
                    edge.put("business_lines", List.copyOf(lines));
                    edges.add(edge);
                }
            }
            result.put("edges", edges);
        }
        result.put("lineage_view", "mapped");
        result.put("scope", "explicit_sources_with_table_mapping");
        result.put("mapping_rule", "table_mapping_and_same_name_field");
        return result;
    }

    private boolean matches(Map<String, Object> item, String needle) {
        if (text(item.get("id")).toUpperCase(Locale.ROOT).contains(needle)
                || text(item.get("qualified_name")).toUpperCase(Locale.ROOT).contains(needle)
                || text(item.get("display_qualified_name")).toUpperCase(Locale.ROOT).contains(needle)
                || text(item.get("chinese_name")).toUpperCase(Locale.ROOT).contains(needle)
                || text(item.get("table_chinese_name")).toUpperCase(Locale.ROOT).contains(needle)) {
            return true;
        }
        Object names = item.get("original_names");
        return names instanceof Collection<?> values
                && values.stream().map(MappedLineageGraph::text)
                .anyMatch(value -> value.toUpperCase(Locale.ROOT).contains(needle));
    }

    private Map<String, Map<String, Object>> buildNodeMetadata(ProjectionPlan plan) {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        Map<String, Map<String, Object>> pendingById = new LinkedHashMap<>();
        pendingFields.forEach(item -> pendingById.put(text(item.get("node_id")), item));
        for (Map.Entry<String, List<Map<String, Object>>> entry : plan.groups.entrySet()) {
            String canonical = entry.getKey();
            List<Map<String, Object>> members = entry.getValue().stream()
                    .sorted(Comparator.comparing(item -> text(item.get("qualified_name")))).toList();
            Map<String, Object> preferred = members.stream()
                    .filter(item -> canonical.equals(item.get("id"))).findFirst().orElse(members.get(0));
            Set<String> sheets = new TreeSet<>();
            boolean changed = false;
            boolean configured = false;
            List<Map<String, Object>> memberRows = new ArrayList<>();
            for (Map<String, Object> member : members) {
                String originalTable = text(member.get("qualified_name")).split("::", 2)[0];
                sheets.addAll(mapper.sheets(originalTable));
                changed |= !mapper.mappedTable(originalTable).equals(originalTable);
                configured |= mapper.mappings().containsKey(originalTable);
                memberRows.add(result("id", member.get("id"), "qualified_name", member.get("qualified_name"),
                        "defined", member.get("defined"), "defined_lines", member.get("defined_lines"),
                        "referenced_lines", member.get("referenced_lines"),
                        "field_count", original.childIds(text(member.get("id"))).size()));
            }
            Map<String, Object> metadata = result("original_qualified_name", preferred.get("qualified_name"),
                    "original_names", members.stream().map(item -> text(item.get("qualified_name"))).toList(),
                    "original_ids", members.stream().map(item -> text(item.get("id"))).toList(),
                    "mapping_member_count", members.size(), "mapping_pending", false,
                    "mapping_grouped", members.stream().anyMatch(item -> !canonical.equals(item.get("id"))),
                    "mapping_members", memberRows, "mapping_sheets", List.copyOf(sheets),
                    "mapping_changed", changed, "mapping_configured", configured,
                    "mapping_status", original.nodeIds().contains(canonical.startsWith(COLUMN_PREFIX)
                            ? text(graph.node(canonical).get("parent_id")) : canonical) ? "connected" : "target_table_missing");
            if (pendingById.containsKey(canonical)) {
                Map<String, Object> pending = pendingById.get(canonical);
                metadata.put("mapping_pending", true);
                metadata.put("mapping_status", pending.get("reason"));
                metadata.put("mapping_message", pending.get("message"));
                metadata.put("mapping_candidate", pending.get("target"));
            }
            result.put(canonical, Map.copyOf(metadata));
        }
        return Map.copyOf(result);
    }

    private Map<String, Object> buildSummary(ProjectionPlan plan) {
        long originalTables = plan.originalNodes.values().stream()
                .filter(item -> "table".equals(item.get("type"))).count();
        long businessTables = plan.groups.entrySet().stream().filter(item -> item.getKey().startsWith(TABLE_PREFIX)).count();
        long groupedTables = plan.groups.entrySet().stream()
                .filter(item -> item.getKey().startsWith(TABLE_PREFIX) && item.getValue().size() > 1).count();
        long matchedFields = plan.originalNodes.values().stream().filter(item -> "column".equals(item.get("type")))
                .filter(item -> !text(item.get("id")).equals(plan.aliases.get(item.get("id")))).count();
        Set<String> pendingTables = new LinkedHashSet<>();
        Map<String, Long> reasons = new TreeMap<>();
        for (Map<String, Object> pending : pendingFields) {
            pendingTables.add(text(pending.get("source")).split("::", 2)[0]);
            reasons.merge(text(pending.get("reason")), 1L, Long::sum);
        }
        return Map.copyOf(result("grouped_tables", groupedTables, "original_tables", originalTables,
                "business_tables", businessTables, "matched_source_fields", matchedFields,
                "pending_fields", pendingFields.size(), "pending_source_tables", pendingTables.size(),
                "pending_reasons", reasons));
    }

    private Map<String, Object> mappingBasis(String... originalIds) {
        List<Map<String, Object>> rows = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String originalId : originalIds) {
            if ((!originalId.startsWith(COLUMN_PREFIX) && !originalId.startsWith(TABLE_PREFIX))
                    || !seen.add(originalId)) {
                continue;
            }
            String value = originalId.substring(originalId.indexOf(':') + 1);
            String originalTable = value.split("::", 2)[0];
            String target = mapper.mappedTable(originalTable);
            if (!target.equals(originalTable)) {
                rows.add(result("source", originalTable, "target", target,
                        "sheets", mapper.sheets(originalTable),
                        "field_status", isPending(aliases.getOrDefault(originalId, originalId))
                                ? "pending" : "same_name"));
            }
        }
        return rows.isEmpty() ? Map.of() : result("storage_source", mapper.summary().get("storage_source"),
                "workbook", mapper.summary().get("workbook"), "fingerprint", mapper.summary().get("fingerprint"),
                "configuration_fingerprint", mapper.summary().get("configuration_fingerprint"),
                "rule", "table_mapping_and_same_name_field", "rows", rows);
    }

    private boolean isPending(String nodeId) {
        return Boolean.TRUE.equals(nodeMetadata.getOrDefault(nodeId, Map.of()).get("mapping_pending"));
    }

    private String optionalResolve(String nodeId) {
        return nodeId == null || nodeId.isBlank() ? "" : resolve(nodeId);
    }

    private static Map<String, Object> copy(Map<?, ?> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> result.put(text(key), value));
        return result;
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static Map<String, Object> result(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            result.put(String.valueOf(values[index]), values[index + 1]);
        }
        return result;
    }

    private static final class ProjectionPlan {
        private final LineageGraph original;
        private final TableNameMapper mapper;
        private final Map<String, Map<String, Object>> originalNodes = new LinkedHashMap<>();
        private final Map<String, String> aliases = new LinkedHashMap<>();
        private final Map<String, LineageGraph.ColumnProjection> columns = new LinkedHashMap<>();
        private final Map<String, List<Map<String, Object>>> groups = new LinkedHashMap<>();
        private final List<Map<String, Object>> pendingFields = new ArrayList<>();

        private ProjectionPlan(LineageGraph original, TableNameMapper mapper) {
            this.original = original;
            this.mapper = mapper;
        }

        private static ProjectionPlan build(LineageGraph original, TableNameMapper mapper) {
            ProjectionPlan plan = new ProjectionPlan(original, mapper);
            for (String id : original.nodeIds()) {
                plan.originalNodes.put(id, original.node(id));
            }
            for (Map<String, Object> node : plan.originalNodes.values()) {
                if ("table".equals(node.get("type"))) {
                    String canonical = TABLE_PREFIX + mapper.mappedTable(text(node.get("qualified_name")));
                    plan.alias(node, canonical);
                } else if ("constant".equals(node.get("type"))) {
                    plan.alias(node, text(node.get("id")));
                }
            }
            for (Map<String, Object> node : plan.originalNodes.values()) {
                if (!"column".equals(node.get("type"))) {
                    continue;
                }
                String originalId = text(node.get("id"));
                String originalParent = text(node.get("parent_id"));
                String parent = plan.aliases.get(originalParent);
                String candidate = COLUMN_PREFIX + parent.substring(TABLE_PREFIX.length()) + "::" + node.get("name");
                boolean pending = !parent.equals(originalParent) && !plan.originalNodes.containsKey(candidate);
                String canonical = pending ? originalId : candidate;
                plan.alias(node, canonical);
                String qualified = canonical.substring(COLUMN_PREFIX.length());
                plan.columns.put(text(node.get("qualified_name")),
                        new LineageGraph.ColumnProjection(qualified, parent.substring(TABLE_PREFIX.length())));
                if (pending) {
                    String reason = plan.originalNodes.containsKey(parent) ? "field_missing" : "target_table_missing";
                    String target = parent.substring(TABLE_PREFIX.length()) + "::" + node.get("name");
                    plan.pendingFields.add(result("source", node.get("qualified_name"), "target", target,
                            "original_id", originalId, "business_table", parent.substring(TABLE_PREFIX.length()),
                            "node_id", canonical, "reason", reason, "sheets", mapper.sheets(
                                    originalParent.substring(TABLE_PREFIX.length())),
                            "message", reason.equals("field_missing")
                                    ? "B表的现有字段记录中未找到同名字段" : "B表尚未出现在已导入数据中"));
                }
            }
            for (String canonical : new LinkedHashSet<>(plan.aliases.values())) {
                plan.aliases.putIfAbsent(canonical, canonical);
            }
            return plan;
        }

        private void alias(Map<String, Object> node, String canonical) {
            aliases.put(text(node.get("id")), canonical);
            groups.computeIfAbsent(canonical, key -> new ArrayList<>()).add(node);
        }

        private LineageGraph.ColumnProjection project(String qualifiedName) {
            LineageGraph.ColumnProjection projection = columns.get(qualifiedName.toUpperCase(Locale.ROOT));
            if (projection != null) {
                return projection;
            }
            String normalized = qualifiedName.toUpperCase(Locale.ROOT);
            int separator = normalized.lastIndexOf("::");
            String table = normalized.substring(0, separator);
            String mapped = mapper.mappedTable(table);
            return new LineageGraph.ColumnProjection(mapped + normalized.substring(separator), mapped);
        }
    }
}
