package com.icbc.lineage.domain.graph;

import com.icbc.lineage.domain.model.LineageSource;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Business labels attached to existing derivation paths. No lineage edge is created here. */
public final class BusinessIndex {

    private static final Set<String> BUSINESS_FIELDS = Set.of("YWGSBM", "YSGSBM", "YWBQ", "BIZ_LABEL",
            "BIZ_LABEL_DESC", "BUSINESS_LABEL", "BUSINESS_LABEL_DESC", "TECH_LABEL", "TECH_LABEL_DESC",
            "TECH_LABLE", "TECH_LABLE_DESC", "BIZ_LABLE", "BIZ_LABLE_DESC", "JSBQ");
    private static final Pattern QUOTED_LITERAL = Pattern.compile("'((?:''|[^'])*)'");
    private static final Pattern QUALIFIED_REFERENCE = Pattern.compile(
            "(?i)(?:[A-Z_][\\w$]*\\.)*([A-Z_][\\w$]*)");
    private static final Pattern RULE = Pattern.compile(
            "(?i)\\b(?:CASE|SELECT|WHEN|THEN|ELSE|END|NULL)\\b|[A-Za-z_]\\w*\\s*\\(|\\|\\|");

    private final LineageGraph graph;
    private final Map<String, Set<String>> targets = new TreeMap<>();
    private final Map<String, Map<String, Set<String>>> tableTargets = new TreeMap<>();
    private final Map<String, List<Map<String, Object>>> branches = new TreeMap<>();
    private final Map<String, Set<Integer>> relationIndexes = new TreeMap<>();
    private final Map<Integer, Map<String, Object>> relationBusiness = new HashMap<>();
    private final Map<Integer, Map<String, Map<String, Object>>> resolutions = new HashMap<>();
    private final Map<String, List<Integer>> definitionsByTarget = new HashMap<>();

    BusinessIndex(LineageGraph graph) {
        this.graph = graph;
        for (int index = 0; index < graph.definitionRecords().size(); index++) {
            String target = text(graph.definitionRecords().get(index).get("target"));
            definitionsByTarget.computeIfAbsent(target.toUpperCase(Locale.ROOT), key -> new ArrayList<>()).add(index);
        }
        for (int index = 0; index < graph.definitionRecords().size(); index++) {
            Map<String, Map<String, Object>> resolved = resolveDefinition(index, new HashSet<>());
            resolutions.put(index, resolved);
            registerBranches(index, resolved);
        }
        for (int index = 0; index < graph.relationRecords().size(); index++) {
            Map<String, Object> relation = graph.relationRecords().get(index);
            int definitionIndex = definitionIndex(relation);
            Map<String, Object> detail = resolutions.getOrDefault(definitionIndex, Map.of())
                    .get(text(relation.get("path")));
            if (detail == null || !acceptedStatus(text(detail.get("status")))) {
                continue;
            }
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> labels = (List<Map<String, Object>>) detail.get("labels");
            List<String> names = labels.stream().map(label -> text(label.get("name"))).filter(name -> !name.isBlank())
                    .distinct().sorted().toList();
            if (names.isEmpty()) {
                continue;
            }
            Map<String, Object> info = result("name", String.join("\n", names), "names", names,
                    "raw_label", detail.get("raw_value"), "inherited_from", detail.get("source_path"),
                    "method", detail.get("method"), "resolution", detail);
            relationBusiness.put(index, info);
            for (String name : names) {
                relationIndexes.computeIfAbsent(name, key -> new LinkedHashSet<>()).add(index);
            }
        }
    }

    public Map<String, Object> relationBusinessInfo(int index) {
        Map<String, Object> detail = relationBusiness.get(index);
        if (detail == null) {
            return Map.of();
        }
        return result("effective_label", detail.get("name"),
                "effective_labels", detail.get("names"),
                "inherited", !text(detail.get("inherited_from")).isEmpty(),
                "source_path", detail.get("inherited_from"), "method", detail.get("method"),
                "resolution", detail.get("resolution"));
    }

    public Set<Integer> relationIndexes(String name, String root) {
        Set<Integer> indexes = new LinkedHashSet<>(relationIndexes.getOrDefault(name, Set.of()));
        if (root != null && !root.isBlank()) {
            indexes.removeIf(index -> !text(graph.relationRecords().get(index).get("target")).equals(root));
        }
        return indexes;
    }

    public int edgeBusinessCount(String level, String source, String target, String name, String root) {
        Set<Integer> allowed = relationIndexes(name, root);
        return (int) graph.edgeRelationIndexes(level, source, target).stream().filter(allowed::contains).count();
    }

    public Map<String, Object> edgeBusinessBreakdown(String level, String source, String target,
                                                      String name, String root) {
        int count = edgeBusinessCount(level, source, target, name, root);
        return result("business_match_count", count, "business_direct_count", count,
                "business_inherited_count", 0);
    }

    public boolean edgeHasBusiness(String level, String source, String target, String name) {
        return edgeBusinessCount(level, source, target, name, "") > 0;
    }

    public Map<String, Object> diagnostics(String root, int offset, int limit) {
        List<String> targetIds = text(graph.node(root).get("type")).equals("table") ? graph.childIds(root) : List.of(root);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map.Entry<Integer, Map<String, Map<String, Object>>> entry : resolutions.entrySet()) {
            Map<String, Object> definition = graph.definitionRecords().get(entry.getKey());
            if (!targetIds.contains(text(definition.get("target")))) {
                continue;
            }
            entry.getValue().forEach((path, detail) -> {
                if (text(detail.get("kind")).equals("blank")
                        && ((List<?>) detail.get("labels")).isEmpty() && ((List<?>) detail.get("issues")).isEmpty()) {
                    return;
                }
                Map<String, Object> row = new LinkedHashMap<>(detail);
                row.put("document", definition.get("document"));
                row.put("target", definition.get("target"));
                row.put("path", path);
                rows.add(row);
            });
        }
        rows.sort(Comparator.comparing(row -> text(row.get("document")) + "\0" + text(row.get("path"))));
        Map<String, Long> counts = new TreeMap<>();
        rows.forEach(row -> counts.merge(text(row.get("status")), 1L, Long::sum));
        return result("root", root, "total", rows.size(), "offset", offset, "status_counts", counts,
                "items", rows.stream().skip(Math.max(0, offset)).limit(Math.max(0, limit)).toList());
    }

    public Map<String, Object> summary(String name, String root) {
        List<Map<String, Object>> selected = records(name, root);
        Set<String> fields = new HashSet<>();
        Set<String> tables = new HashSet<>();
        selected.forEach(branch -> {
            String target = text(branch.get("target"));
            fields.add(target);
            tables.add(text(graph.node(target).get("parent_id")));
        });
        return result("name", name, "table_count", tables.size(), "field_count", fields.size(),
                "branch_count", selected.size(), "evidence_count", relationIndexes(name, root).size(),
                "branches_without_sources", selected.stream()
                        .filter(branch -> ((Number) branch.get("source_count")).intValue() == 0).count());
    }

    public Map<String, Object> catalog(String query, int offset, int limit, String root) {
        String needle = text(query).toLowerCase(Locale.ROOT);
        List<String> names = targets.keySet().stream().filter(name -> name.toLowerCase(Locale.ROOT).contains(needle))
                .filter(name -> root == null || root.isBlank() || matches(name, root)).sorted().toList();
        List<Map<String, Object>> items = names.stream().skip(Math.max(0, offset)).limit(Math.max(0, limit))
                .map(name -> summary(name, root)).toList();
        return result("total", names.size(), "offset", offset, "root", text(root), "items", items);
    }

    public List<Map<String, Object>> records(String name, String root) {
        return branches.getOrDefault(name, List.of()).stream().filter(branch -> root == null || root.isBlank()
                        || text(branch.get("target")).equals(root)
                        || text(graph.node(text(branch.get("target"))).get("parent_id")).equals(root))
                .toList();
    }

    public boolean matches(String name, String nodeId) {
        return targets.getOrDefault(name, Set.of()).contains(nodeId)
                || tableTargets.getOrDefault(name, Map.of()).containsKey(nodeId);
    }

    public Set<String> matchingNodeIds(String name) {
        Set<String> result = new LinkedHashSet<>(targets.getOrDefault(name, Set.of()));
        result.addAll(tableTargets.getOrDefault(name, Map.of()).keySet());
        return Set.copyOf(result);
    }

    public Map<String, Object> nodeInfo(String name, String nodeId) {
        Set<String> fields = tableTargets.getOrDefault(name, Map.of()).get(nodeId);
        int fieldCount = fields == null ? (targets.getOrDefault(name, Set.of()).contains(nodeId) ? 1 : 0) : fields.size();
        long branchCount = records(name, nodeId).size();
        long evidenceCount = relationIndexes(name, "").stream().filter(index -> {
            Map<String, Object> relation = graph.relationRecords().get(index);
            return text(relation.get("target")).equals(nodeId)
                    || text(graph.node(text(relation.get("target"))).get("parent_id")).equals(nodeId);
        }).count();
        return result("business_match", matches(name, nodeId), "business_field_count", fieldCount,
                "business_branch_count", branchCount, "business_evidence_count", evidenceCount);
    }

    public Map<String, Object> annotate(Map<String, Object> data, String name) {
        if (name == null || name.isBlank()) {
            return data;
        }
        String level = text(data.get("level"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sourceNodes = (List<Map<String, Object>>) data.get("nodes");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sourceEdges = (List<Map<String, Object>>) data.get("edges");
        List<Map<String, Object>> nodes = sourceNodes.stream().map(node -> {
            Map<String, Object> item = new LinkedHashMap<>(node);
            item.putAll(nodeInfo(name, text(node.get("id"))));
            return item;
        }).toList();
        List<Map<String, Object>> edges = sourceEdges.stream().map(edge -> {
            Map<String, Object> item = new LinkedHashMap<>(edge);
            item.putAll(edgeBusinessBreakdown(level, text(edge.get("source")), text(edge.get("target")), name, ""));
            return item;
        }).toList();
        Map<String, Object> result = new LinkedHashMap<>(data);
        result.put("nodes", nodes);
        result.put("edges", edges);
        result.put("business_tag", name);
        result.put("business_scope", "object_lineage_with_explicit_branch_highlights");
        result.put("business_propagated", false);
        result.put("business_summary", summary(name, ""));
        return result;
    }

    public Map<String, Object> branchList(String name, String root, int offset, int limit) {
        List<Map<String, Object>> selected = records(name, root);
        return result("tag", name, "root", root, "total", selected.size(), "offset", offset,
                "items", selected.stream().skip(Math.max(0, offset)).limit(Math.max(0, limit)).toList());
    }

    public Map<String, Object> fieldUpstream(String root, String name, int depth, int maxNodes,
                                             boolean includeConstants, int maxEdges) {
        return fieldLineage(root, name, "upstream", depth, maxNodes, includeConstants, maxEdges);
    }

    public Map<String, Object> fieldLineage(String root, String name, String direction, int depth,
                                            int maxNodes, boolean includeConstants, int maxEdges) {
        if (!text(graph.node(root).get("type")).equals("column")) {
            throw new IllegalArgumentException("Select a column before querying business lineage");
        }
        if (!matches(name, root)) {
            throw new IllegalArgumentException("The selected column has no record for this business label");
        }
        Set<Integer> direct = relationIndexes(name, root);
        LineageGraph.EdgePredicate allowed = (source, target, traversal) -> graph.valueEdge(source, target)
                && (traversal.equals("downstream") || !target.equals(root)
                || graph.edgeRelationIndexes("column", source, target).stream().anyMatch(direct::contains));
        Map<String, Object> data = graph.subgraph(root, direction, depth, maxNodes,
                includeConstants, maxEdges, allowed);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sourceNodes = (List<Map<String, Object>>) data.get("nodes");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sourceEdges = (List<Map<String, Object>>) data.get("edges");
        List<Map<String, Object>> nodes = sourceNodes.stream().map(node -> {
            Map<String, Object> item = new LinkedHashMap<>(node);
            item.put("business_match", text(node.get("id")).equals(root));
            return item;
        }).toList();
        List<Map<String, Object>> edges = sourceEdges.stream().map(edge -> {
            int count = text(edge.get("target")).equals(root)
                    ? (int) graph.edgeRelationIndexes("column", text(edge.get("source")), root).stream()
                    .filter(direct::contains).count() : 0;
            Map<String, Object> item = new LinkedHashMap<>(edge);
            item.putAll(result("business_match_count", count, "business_direct_count", count,
                    "business_inherited_count", 0));
            return item;
        }).toList();
        Map<String, Object> result = new LinkedHashMap<>(data);
        result.put("nodes", nodes);
        result.put("edges", edges);
        result.put("business_tag", name);
        result.put("business_scope", direction.equals("upstream")
                ? "selected_field_business_upstream" : "selected_field_business_lineage");
        result.put("business_summary", summary(name, root));
        result.put("deeper_upstream_scope", "complete_lineage_of_selected_sources");
        result.put("downstream_scope", "all_field_references_unfiltered");
        result.put("business_propagated", false);
        boolean candidate = records(name, root).stream()
                .anyMatch(branch -> Set.of("candidate", "partial").contains(text(branch.get("label_status"))));
        result.put("candidate_business", candidate);
        result.put("business_certainty", candidate ? "candidate" : "resolved");
        return result;
    }

    public static String businessName(Object value, String line) {
        if (!(value instanceof String)) {
            return "";
        }
        String text = ((String) value).trim();
        Matcher alias = Pattern.compile("(?is)^(['\"])(.*)\\1\\s+(?:AS\\s+)?(?:YWGSBM|YSGSBM|BIZ_LABEL|BIZ_LABEL_DESC|YWBQ)$")
                .matcher(text);
        if (alias.matches()) {
            text = alias.group(2).replace(alias.group(1) + alias.group(1), alias.group(1)).trim();
        } else if (text.length() >= 2 && (text.charAt(0) == '\'' || text.charAt(0) == '"')
                && text.charAt(text.length() - 1) == text.charAt(0)) {
            text = text.substring(1, text.length() - 1).trim();
        }
        if (text.isBlank() || text.equalsIgnoreCase("null") || text.matches("(?is)^CONST:\\s*(?:['\"]?\\s*(?:NULL)?\\s*['\"]?)?$")) {
            return "";
        }
        String last = lastIdentifier(text);
        if (BUSINESS_FIELDS.contains(last) && text.matches("(?i)^[\"A-Z_][\\w$\"]*(?:\\s*\\.\\s*[\"A-Z_][\\w$\"]*)*(?:\\s+(?:AS\\s+)?[A-Z_][\\w$]*)?$")) {
            return "";
        }
        if (text(line).equalsIgnoreCase("east")) {
            return text;
        }
        if (text.length() > 200 || RULE.matcher(text).find() || text.startsWith("$") || text.startsWith(":")
                || text.matches("[A-Za-z_][\\w$]*(?:\\.[A-Za-z_][\\w$]*)+")) {
            return "";
        }
        return text;
    }

    private void registerBranches(int definitionIndex, Map<String, Map<String, Object>> resolved) {
        Map<String, Object> definition = graph.definitionRecords().get(definitionIndex);
        @SuppressWarnings("unchecked")
        List<LineageSource> sources = (List<LineageSource>) definition.get("sources");
        Map<String, Long> sourceCounts = new HashMap<>();
        sources.forEach(source -> sourceCounts.merge(source.path(), 1L, Long::sum));
        String target = text(definition.get("target"));
        String table = text(graph.node(target).get("parent_id"));
        resolved.forEach((path, detail) -> {
            if (!acceptedStatus(text(detail.get("status")))) {
                return;
            }
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> labels = (List<Map<String, Object>>) detail.get("labels");
            labels.stream().map(label -> text(label.get("name"))).filter(name -> !name.isBlank()).distinct().forEach(name -> {
                targets.computeIfAbsent(name, key -> new LinkedHashSet<>()).add(target);
                tableTargets.computeIfAbsent(name, key -> new TreeMap<>())
                        .computeIfAbsent(table, key -> new LinkedHashSet<>()).add(target);
                Map<String, Object> branch = result("document", definition.get("document"),
                        "derivation_index", definition.get("index"), "target", target, "path", path,
                        "raw_label", detail.get("raw_value"), "source_count", sourceCounts.getOrDefault(path, 0L),
                        "effective_label", name, "business_line", definition.get("business_line"),
                        "original_target", target, "label_method", detail.get("method"),
                        "label_status", detail.get("status"), "label_values", labels,
                        "label_issues", detail.get("issues"));
                if (!text(detail.get("source_path")).isEmpty()) {
                    branch.put("business_tag_inherited", true);
                    branch.put("business_tag_source_path", detail.get("source_path"));
                }
                branches.computeIfAbsent(name, key -> new ArrayList<>()).add(branch);
            });
        });
    }

    private Map<String, Map<String, Object>> resolveDefinition(int definitionIndex, Set<Integer> trail) {
        if (!trail.add(definitionIndex)) {
            return Map.of();
        }
        Map<String, Object> definition = graph.definitionRecords().get(definitionIndex);
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> logic = (Map<String, Map<String, Object>>) definition.get("transform_logic");
        @SuppressWarnings("unchecked")
        List<LineageSource> sources = (List<LineageSource>) definition.get("sources");
        Set<String> paths = new LinkedHashSet<>(logic.keySet());
        sources.forEach(source -> paths.add(source.path()));
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        for (String path : paths.stream().sorted().toList()) {
            Map<String, Object> pathLogic = logic.getOrDefault(path, Map.of());
            LabelValue raw = rawLabel(pathLogic);
            String kind = classify(raw.value);
            List<Map<String, Object>> labels = new ArrayList<>();
            List<String> issues = new ArrayList<>();
            String status = "unresolved";
            String method = "direct";
            if (kind.equals("literal")) {
                String name = businessName(raw.value, text(definition.get("business_line")));
                if (!name.isEmpty()) {
                    labels.add(label(name));
                    status = raw.key.equals("BIZ_LABEL") && name.matches("(?i)STD_[A-Z0-9_]+")
                            ? "technical" : "resolved";
                }
            } else if (kind.equals("reference") || kind.equals("rule")) {
                Set<String> candidates = dynamicLabels(text(raw.value), sources, trail);
                candidates.forEach(name -> labels.add(label(name)));
                if (!labels.isEmpty()) {
                    status = "candidate";
                } else {
                    issues.add("Business label expression could not be resolved conservatively");
                }
                method = kind;
            } else if (kind.equals("empty")) {
                status = "empty";
            }
            Map<String, Object> row = result("raw_key", raw.key, "raw_value", raw.value, "kind", kind,
                    "method", method, "source_path", "", "status", status, "labels", labels, "issues", issues,
                    "source_count", sources.stream().filter(source -> source.path().equals(path)).count());
            result.put(path, row);
        }
        for (String path : paths) {
            Map<String, Object> row = result.get(path);
            if (!text(row.get("kind")).equals("blank")) {
                continue;
            }
            String parent = path;
            while (parent.contains("-")) {
                parent = parent.substring(0, parent.lastIndexOf('-'));
                Map<String, Object> ancestor = result.get(parent);
                if (ancestor == null || text(ancestor.get("kind")).equals("blank")) {
                    continue;
                }
                if (Set.of("resolved", "technical").contains(text(ancestor.get("status")))) {
                    row.put("labels", ancestor.get("labels"));
                    row.put("status", ancestor.get("status"));
                    row.put("method", "ancestor");
                    row.put("source_path", parent);
                }
                break;
            }
        }
        trail.remove(definitionIndex);
        return result;
    }

    private Set<String> dynamicLabels(String expression, List<LineageSource> sources, Set<Integer> trail) {
        Set<String> labels = new LinkedHashSet<>();
        Matcher literals = QUOTED_LITERAL.matcher(expression);
        while (literals.find()) {
            String candidate = literals.group(1).replace("''", "'").trim();
            if (!candidate.isEmpty() && !candidate.matches("[0-9]{4,}") && !candidate.matches("[0-9.]+")) {
                labels.add(candidate);
            }
        }
        String referencedField = lastIdentifier(expression);
        if (BUSINESS_FIELDS.contains(referencedField)) {
            List<String> references = new ArrayList<>();
            for (LineageSource source : sources) {
                if (source.ref() != null
                        && source.ref().toUpperCase(Locale.ROOT).endsWith("::" + referencedField)
                        && !references.contains(source.ref())) {
                    references.add(source.ref());
                }
            }
            if (references.size() == 1) {
                for (int definitionIndex : definitionsByTarget.getOrDefault(
                        "column:" + references.get(0).toUpperCase(Locale.ROOT), List.of())) {
                    for (Map<String, Object> row : resolveDefinition(definitionIndex, trail).values()) {
                        @SuppressWarnings("unchecked")
                        List<Map<String, Object>> values = (List<Map<String, Object>>) row.get("labels");
                        for (Map<String, Object> value : values) {
                            labels.add(text(value.get("name")));
                        }
                    }
                }
            }
        }
        return labels;
    }

    private LabelValue rawLabel(Map<String, Object> logic) {
        for (String key : List.of("YWGSBM", "YSGSBM", "BIZ_LABEL")) {
            for (Map.Entry<String, Object> entry : logic.entrySet()) {
                if (entry.getKey().equalsIgnoreCase(key) && entry.getValue() != null
                        && (!(entry.getValue() instanceof String value) || !value.isBlank())) {
                    return new LabelValue(key, entry.getValue());
                }
            }
        }
        return new LabelValue("", null);
    }

    private String classify(Object value) {
        if (value == null || value instanceof String text && text.isBlank()) {
            return "blank";
        }
        if (!(value instanceof String)) {
            return "unknown";
        }
        String text = ((String) value).trim();
        if (text.toUpperCase(Locale.ROOT).startsWith("CONST:")) {
            text = text.substring(6).trim();
        }
        text = text.replaceFirst("(?i)\\s+(?:AS\\s+)?(?:YWGSBM|YSGSBM|BIZ_LABEL|YWBQ)$", "");
        if (text.length() >= 2 && (text.charAt(0) == '\'' || text.charAt(0) == '"')
                && text.charAt(text.length() - 1) == text.charAt(0)) {
            text = text.substring(1, text.length() - 1).trim();
        }
        if (text.isEmpty() || text.equalsIgnoreCase("null")) {
            return "empty";
        }
        String last = lastIdentifier(text);
        if (BUSINESS_FIELDS.contains(last) || text.matches("(?i)[A-Z_][\\w$]*(?:\\s*\\.\\s*[A-Z_][\\w$]*)+")) {
            return "reference";
        }
        if (RULE.matcher(text).find() || text.startsWith("$") || text.startsWith(":")) {
            return "rule";
        }
        return "literal";
    }

    private int definitionIndex(Map<String, Object> relation) {
        String document = text(relation.get("document"));
        int index = ((Number) relation.get("derivation_index")).intValue();
        for (int position = 0; position < graph.definitionRecords().size(); position++) {
            Map<String, Object> definition = graph.definitionRecords().get(position);
            if (text(definition.get("document")).equals(document)
                    && ((Number) definition.get("index")).intValue() == index) {
                return position;
            }
        }
        throw new IllegalStateException("Relation points to a missing derivation");
    }

    private boolean acceptedStatus(String status) {
        return Set.of("resolved", "candidate", "partial").contains(status);
    }

    private static String lastIdentifier(String value) {
        Matcher matcher = QUALIFIED_REFERENCE.matcher(text(value).trim());
        String last = "";
        while (matcher.find()) {
            last = matcher.group(1).replace("\"", "").toUpperCase(Locale.ROOT);
        }
        return last;
    }

    private static Map<String, Object> label(String name) {
        return result("name", name, "conditions", List.of(), "evidence", List.of());
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

    private static final class LabelValue {
        private final String key;
        private final Object value;

        private LabelValue(String key, Object value) {
            this.key = key;
            this.value = value;
        }
    }
}
