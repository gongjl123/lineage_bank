package com.icbc.lineage.domain.graph;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.icbc.lineage.common.DigestUtils;
import com.icbc.lineage.domain.model.BusinessLine;
import com.icbc.lineage.domain.model.Derivation;
import com.icbc.lineage.domain.model.LineageDocument;
import com.icbc.lineage.domain.model.LineageIssue;
import com.icbc.lineage.domain.model.LineageSource;
import com.icbc.lineage.domain.model.SourceKind;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Immutable lineage graph snapshot built from the currently imported documents.
 * Relations always point from a source to a target.
 */
public final class LineageGraph {

    @FunctionalInterface
    public interface EdgePredicate {
        boolean test(String source, String target, String traversal);
    }

    @FunctionalInterface
    interface ColumnProjector {
        ColumnProjection project(String qualifiedName);
    }

    record ColumnProjection(String qualifiedName, String parentTable) {
        static ColumnProjection identity(String qualifiedName) {
            int separator = qualifiedName.lastIndexOf("::");
            return new ColumnProjection(qualifiedName, qualifiedName.substring(0, separator));
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String TABLE = "table";
    private static final String COLUMN = "column";
    private static final String CONSTANT = "constant";

    private final Map<String, Node> nodes = new LinkedHashMap<>();
    private final List<Map<String, Object>> relations = new ArrayList<>();
    private final List<Map<String, Object>> derivations = new ArrayList<>();
    private final List<LineageIssue> issues = new ArrayList<>();
    private final Map<String, Map<String, List<Integer>>> tableOutgoing = new HashMap<>();
    private final Map<String, Map<String, List<Integer>>> tableIncoming = new HashMap<>();
    private final Map<String, Map<String, List<Integer>>> columnOutgoing = new HashMap<>();
    private final Map<String, Map<String, List<Integer>>> columnIncoming = new HashMap<>();
    private final Map<String, List<Integer>> byTarget = new HashMap<>();
    private final Map<String, List<String>> children = new HashMap<>();
    private final Map<String, List<List<String>>> cycles = new LinkedHashMap<>();
    private final Map<String, Set<String>> multiNodeCycleMembers = new HashMap<>();
    private final Map<Integer, Boolean> valueRelationCache = new HashMap<>();
    private final ValueLineageDecider valueLineageDecider;
    private final int documentCount;
    private final BusinessIndex business;
    private final List<LineageDocument> sourceDocuments;
    private final List<LineageIssue> initialIssues;
    private final ColumnProjector columnProjector;
    private final boolean projected;

    public LineageGraph(List<LineageDocument> documents, List<LineageIssue> initialIssues) {
        this(documents, initialIssues, relation -> true);
    }

    public LineageGraph(List<LineageDocument> documents, List<LineageIssue> initialIssues,
                        ValueLineageDecider valueLineageDecider) {
        this(documents, initialIssues, valueLineageDecider, ColumnProjection::identity, false);
    }

    LineageGraph(List<LineageDocument> documents, List<LineageIssue> initialIssues,
                 ValueLineageDecider valueLineageDecider, ColumnProjector columnProjector,
                 boolean projected) {
        this.documentCount = documents.size();
        this.sourceDocuments = List.copyOf(documents);
        this.initialIssues = List.copyOf(initialIssues);
        this.valueLineageDecider = Objects.requireNonNull(valueLineageDecider, "valueLineageDecider");
        this.columnProjector = Objects.requireNonNull(columnProjector, "columnProjector");
        this.projected = projected;
        initialIssues.stream()
                .filter(item -> !item.code().equals("EMPTY_SOURCES") && !item.code().equals("EMPTY_SOURCES_USED"))
                .forEach(issues::add);
        Map<String, Set<String>> targetDocuments = new HashMap<>();
        for (LineageDocument document : documents) {
            addDocument(document, targetDocuments);
        }
        finalizeSnapshot(targetDocuments);
        this.business = new BusinessIndex(this);
    }

    public String resolve(String nodeId) {
        if (!nodes.containsKey(nodeId)) {
            throw new IllegalArgumentException("Unknown lineage node: " + nodeId);
        }
        return nodeId;
    }

    public Map<String, Object> node(String nodeId) {
        Node node = requiredNode(nodeId);
        String level = level(nodeId);
        Map<String, Map<String, List<Integer>>> incoming = incoming(level);
        Map<String, Map<String, List<Integer>>> outgoing = outgoing(level);
        Map<String, List<Integer>> upstream = incoming.getOrDefault(nodeId, Map.of());
        Map<String, List<Integer>> downstream = outgoing.getOrDefault(nodeId, Map.of());
        Map<String, Object> result = node.toMap();
        result.put("upstream_count", upstream.size() - (upstream.containsKey(nodeId) ? 1 : 0));
        result.put("downstream_count", downstream.size() - (downstream.containsKey(nodeId) ? 1 : 0));
        result.put("field_count", children.getOrDefault(nodeId, List.of()).size());
        result.put("self_reference_count", downstream.getOrDefault(nodeId, List.of()).size());
        result.put("multi_node_cycle", multiNodeCycleMembers.getOrDefault(level, Set.of()).contains(nodeId));
        return result;
    }

    public Map<String, Object> stats() {
        Map<String, Long> types = nodes.values().stream()
                .collect(Collectors.groupingBy(node -> node.type, Collectors.counting()));
        Map<String, Long> issueCounts = issues.stream()
                .collect(Collectors.groupingBy(LineageIssue::code, LinkedHashMap::new, Collectors.counting()));
        Map<String, Integer> cycleCounts = new LinkedHashMap<>();
        cycles.forEach((key, value) -> cycleCounts.put(key, value.size()));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("documents", documentCount);
        result.put("entities", nodes.size());
        result.put("tables", types.getOrDefault(TABLE, 0L));
        result.put("columns", types.getOrDefault(COLUMN, 0L));
        result.put("constants", types.getOrDefault(CONSTANT, 0L));
        result.put("relations", relations.size());
        result.put("derivations", derivations.size());
        result.put("table_edges", edgeCount(tableOutgoing));
        result.put("column_edges", edgeCount(columnOutgoing));
        result.put("external_columns", nodes.values().stream().filter(n -> n.type.equals(COLUMN) && !n.defined).count());
        result.put("external_tables", nodes.values().stream().filter(n -> n.type.equals(TABLE) && !n.defined).count());
        result.put("issues", issues.size());
        result.put("issue_counts", issueCounts);
        result.put("cycles", cycleCounts);
        result.put("namespaces", nodes.values().stream().filter(n -> !n.type.equals(CONSTANT)).map(n -> n.namespace)
                .distinct().sorted().toList());
        return result;
    }

    public Map<String, Object> search(String query, String kind, String namespace, String status,
                                      String parentId, int offset, int limit, String line,
                                      Set<String> visibleNodes) {
        String needle = valueOrEmpty(query).toUpperCase(Locale.ROOT);
        Collection<Node> candidates = parentId == null || parentId.isBlank()
                ? nodes.values() : children.getOrDefault(parentId, List.of()).stream().map(nodes::get).toList();
        List<Node> matched = candidates.stream()
                .filter(Objects::nonNull)
                .filter(n -> visibleNodes == null || visibleNodes.contains(n.id))
                .filter(n -> kind == null || kind.equals("all") || n.type.equals(kind))
                .filter(n -> needle.isEmpty() || n.id.contains(needle) || n.qualifiedName.contains(needle))
                .filter(n -> namespace == null || namespace.isBlank() || n.namespace.equals(namespace))
                .filter(n -> status == null || status.equals("all") || status.equals("defined") == n.defined)
                .filter(n -> line == null || line.isBlank() || n.definedLines.contains(line))
                .sorted(Comparator.comparing((Node n) -> !n.name.equals(needle))
                        .thenComparing(n -> n.qualifiedName))
                .toList();
        List<Map<String, Object>> items = matched.stream().skip(Math.max(0, offset)).limit(Math.max(0, limit))
                .map(n -> node(n.id)).toList();
        return result("total", matched.size(), "offset", offset, "items", items);
    }

    public boolean valueEdge(String source, String target) {
        for (int index : columnOutgoing.getOrDefault(source, Map.of()).getOrDefault(target, List.of())) {
            if (valueRelationCache.computeIfAbsent(index,
                    key -> valueLineageDecider.suppliesTargetValue(Collections.unmodifiableMap(relations.get(key))))) {
                return true;
            }
        }
        return false;
    }

    public Map<String, Object> subgraph(String root, String direction, int depth, int maxNodes,
                                        boolean includeConstants, int maxEdges,
                                        EdgePredicate edgeAllowed) {
        requiredNode(root);
        List<String> directions = direction.equals("both")
                ? List.of("upstream", "downstream") : List.of(direction);
        Set<String> chosen = new LinkedHashSet<>();
        chosen.add(root);
        Map<String, Integer> distances = new HashMap<>();
        distances.put(root, 0);
        Map<String, String> parents = new HashMap<>();
        Set<String> reasons = new TreeSet<>();
        Set<String> boundary = new TreeSet<>();
        Map<String, Traversal> traversals = new LinkedHashMap<>();
        for (String traversalDirection : directions) {
            Traversal traversal = walk(root, traversalDirection, depth, maxNodes, includeConstants, edgeAllowed);
            traversals.put(traversalDirection, traversal);
            reasons.addAll(traversal.reasons());
            boundary.addAll(traversal.boundary());
            int sign = traversalDirection.equals("upstream") ? -1 : 1;
            for (Map.Entry<String, Integer> item : traversal.distance().entrySet()) {
                if (!chosen.contains(item.getKey()) && chosen.size() >= maxNodes) {
                    reasons.add("node_limit");
                    continue;
                }
                chosen.add(item.getKey());
                if (!distances.containsKey(item.getKey())) {
                    distances.put(item.getKey(), item.getValue() * sign);
                    parents.put(item.getKey(), traversal.parent().get(item.getKey()));
                }
            }
        }
        String level = level(root);
        Set<EdgePair> edgePairs = new TreeSet<>();
        List<FrontierEdge> frontierEdges = new ArrayList<>();
        for (Map.Entry<String, Traversal> entry : traversals.entrySet()) {
            boolean upstream = entry.getKey().equals("upstream");
            Map<String, Map<String, List<Integer>>> adjacency = upstream ? incoming(level) : outgoing(level);
            for (String current : new TreeSet<>(chosen)) {
                if (!entry.getValue().distance().containsKey(current)) {
                    continue;
                }
                for (String neighbor : adjacency.getOrDefault(current, Map.of()).keySet()) {
                    if (level.equals(TABLE) && current.equals(neighbor)) {
                        continue;
                    }
                    if (!includeConstants && requiredNode(neighbor).type.equals(CONSTANT)) {
                        continue;
                    }
                    EdgePair pair = upstream ? new EdgePair(neighbor, current) : new EdgePair(current, neighbor);
                    if (edgeAllowed != null && !edgeAllowed.test(pair.source(), pair.target(), entry.getKey())) {
                        continue;
                    }
                    if (entry.getValue().distance().get(current) >= depth) {
                        frontierEdges.add(new FrontierEdge(current, pair));
                    } else if (chosen.contains(neighbor) && entry.getValue().distance().containsKey(neighbor)) {
                        edgePairs.add(pair);
                    }
                }
            }
        }
        for (FrontierEdge frontier : frontierEdges) {
            if (!edgePairs.contains(frontier.pair())) {
                reasons.add("depth_limit");
                boundary.add(frontier.node());
            }
        }
        int totalEdges = edgePairs.size();
        List<Map<String, Object>> edges = edgePairs.stream().limit(maxEdges)
                .map(pair -> edge(level, pair.source(), pair.target())).toList();
        if (totalEdges > maxEdges) {
            reasons.add("edge_limit");
        }
        List<Map<String, Object>> resultNodes = new TreeSet<>(chosen).stream().map(id -> {
            Map<String, Object> item = node(id);
            item.put("distance", distances.getOrDefault(id, 0));
            item.put("predecessor", parents.get(id));
            return item;
        }).toList();
        Map<String, Object> result = result("root", root, "direction", direction, "depth", depth,
                "level", level, "nodes", resultNodes, "edges", edges, "total_edges", totalEdges,
                "truncated", !reasons.isEmpty(), "reasons", List.copyOf(reasons),
                "boundary", boundary.stream().filter(chosen::contains).toList());
        result.put("scope", "explicit_sources");
        result.put("edge_scope", "traversed_within_depth");
        result.put("table_self_edges_hidden", level.equals(TABLE));
        result.put("approximation", level.equals(TABLE));
        return result;
    }

    public Map<String, Object> impact(String root, int depth, int maxNodes) {
        Traversal traversal = walk(root, "downstream", depth, maxNodes, false, null);
        List<String> affected = traversal.distance().keySet().stream().filter(id -> !id.equals(root))
                .sorted(Comparator.<String>comparingInt(traversal.distance()::get)
                        .thenComparing(Comparator.naturalOrder())).toList();
        List<String> tables = affected.stream().map(id -> requiredNode(id).type.equals(TABLE) ? id : requiredNode(id).parentId)
                .filter(Objects::nonNull).distinct().sorted().toList();
        List<Map<String, Object>> items = affected.stream().map(id -> {
            Map<String, Object> item = node(id);
            item.put("distance", traversal.distance().get(id));
            item.put("predecessor", traversal.parent().get(id));
            return item;
        }).toList();
        Map<String, Object> result = result("root", root, "total", affected.size(),
                "direct", affected.stream().filter(id -> traversal.distance().get(id) == 1).count(),
                "indirect", affected.stream().filter(id -> traversal.distance().get(id) > 1).count(),
                "affected_tables", tables, "items", items, "truncated", !traversal.reasons().isEmpty(),
                "reasons", List.copyOf(traversal.reasons()), "boundary", List.copyOf(traversal.boundary()));
        result.put("approximation", level(root).equals(TABLE));
        result.put("scope", "已导入 JSON 的显式 sources；表级为保守影响范围。");
        return result;
    }

    public Map<String, Object> associatedTables(String root, int maxNodes) {
        Map<String, Traversal> traversals = new LinkedHashMap<>();
        traversals.put("upstream", walk(root, "upstream", nodes.size(), maxNodes, false, null));
        traversals.put("downstream", walk(root, "downstream", nodes.size(), maxNodes, false, null));
        Map<String, Object> summary = new LinkedHashMap<>();
        Set<String> reasons = new TreeSet<>();
        for (Map.Entry<String, Traversal> entry : traversals.entrySet()) {
            List<Integer> distances = entry.getValue().distance().entrySet().stream()
                    .filter(item -> !item.getKey().equals(root)).map(Map.Entry::getValue).toList();
            summary.put(entry.getKey(), result("total", distances.size(),
                    "direct", distances.stream().filter(value -> value == 1).count(),
                    "indirect", distances.stream().filter(value -> value > 1).count(),
                    "max_distance", distances.stream().max(Integer::compareTo).orElse(0),
                    "truncated", !entry.getValue().reasons().isEmpty()));
            reasons.addAll(entry.getValue().reasons());
        }
        Set<String> reached = new HashSet<>(traversals.get("upstream").distance().keySet());
        reached.addAll(traversals.get("downstream").distance().keySet());
        reached.remove(root);
        List<Map<String, Object>> items = reached.stream().map(id -> {
            Map<String, Object> item = node(id);
            item.put("upstream_distance", traversals.get("upstream").distance().get(id));
            item.put("downstream_distance", traversals.get("downstream").distance().get(id));
            return item;
        }).sorted(Comparator.comparingInt(this::minimumDistance).thenComparing(item -> String.valueOf(item.get("id"))))
                .toList();
        Map<String, Object> result = result("root", root, "total", items.size(), "summary", summary,
                "items", items, "truncated", !reasons.isEmpty(), "reasons", List.copyOf(reasons));
        result.put("scope", "explicit_sources");
        result.put("distance_basis", "shortest_path");
        result.put("excludes_root", true);
        return result;
    }

    public Map<String, Object> tablePath(String root, String target, String direction, int maxNodes) {
        if (!level(root).equals(TABLE) || !level(target).equals(TABLE)) {
            throw new IllegalArgumentException("Table path requires table nodes");
        }
        Traversal traversal = walk(root, direction, nodes.size(), maxNodes, false, null);
        if (target.equals(root) || !traversal.parent().containsKey(target)) {
            throw new IllegalArgumentException(traversal.reasons().isEmpty()
                    ? "所选方向没有找到关联路径；请刷新关联表清单。" : "查询已达节点上限，暂未找到该路径。");
        }
        List<String> path = new ArrayList<>();
        path.add(target);
        while (!path.get(path.size() - 1).equals(root)) {
            path.add(traversal.parent().get(path.get(path.size() - 1)));
        }
        if (direction.equals("downstream")) {
            Collections.reverse(path);
        }
        if (path.size() > 300) {
            throw new IllegalArgumentException("路径超过 300 个节点，请在关联清单中分段查看。");
        }
        int sign = direction.equals("upstream") ? -1 : 1;
        List<Map<String, Object>> resultNodes = path.stream().map(id -> {
            Map<String, Object> item = node(id);
            item.put("distance", sign * traversal.distance().get(id));
            return item;
        }).toList();
        List<Map<String, Object>> edges = new ArrayList<>();
        for (int index = 0; index < path.size() - 1; index++) {
            edges.add(edge(TABLE, path.get(index), path.get(index + 1)));
        }
        Map<String, Object> result = result("root", root, "target", target, "direction", direction,
                "level", TABLE, "depth", traversal.distance().get(target), "path", path, "path_mode", true,
                "nodes", resultNodes, "edges", edges, "total_edges", edges.size(), "truncated", false,
                "reasons", List.of());
        result.put("approximation", true);
        result.put("scope", "explicit_sources");
        result.put("path_kind", "one_shortest_path");
        return result;
    }

    public Map<String, Object> evidence(String root, String source, String target,
                                        int offset, int limit, String query) {
        String level = level(root);
        Set<Integer> indices = new TreeSet<>();
        if (source != null && !source.isBlank() && target != null && !target.isBlank()) {
            indices.addAll(outgoing(level).getOrDefault(source, Map.of()).getOrDefault(target, List.of()));
        } else {
            incoming(level).getOrDefault(root, Map.of()).values().forEach(indices::addAll);
            outgoing(level).getOrDefault(root, Map.of()).values().forEach(indices::addAll);
        }
        int unfilteredTotal = indices.size();
        String needle = valueOrEmpty(query).trim().toLowerCase(Locale.ROOT);
        List<Map<String, Object>> matched = indices.stream().map(index -> evidenceItem(index))
                .filter(item -> needle.isEmpty() || matchesText(item, needle)).toList();
        long upstream = 0;
        long downstream = 0;
        long internal = 0;
        for (Map<String, Object> item : matched) {
            String relationSource = String.valueOf(item.get("source"));
            String relationTarget = String.valueOf(item.get("target"));
            if (level.equals(TABLE)) {
                relationSource = requiredNode(relationSource).parentId;
                relationTarget = requiredNode(relationTarget).parentId;
            }
            if (root.equals(relationSource) && root.equals(relationTarget)) {
                internal++;
            } else if (root.equals(relationTarget)) {
                upstream++;
            } else if (root.equals(relationSource)) {
                downstream++;
            }
        }
        List<Map<String, Object>> items = matched.stream().skip(Math.max(0, offset)).limit(Math.max(0, limit)).toList();
        Map<String, Object> result = result("total", matched.size(), "unfiltered_total", unfilteredTotal,
                "direct_upstream", upstream, "direct_downstream", downstream, "internal", internal,
                "scope", "direct_relations", "query", valueOrEmpty(query).trim(), "offset", offset, "items", items);
        return result;
    }

    public Map<String, Object> definitions(String root, int offset, int limit, String query) {
        List<String> targets = level(root).equals(TABLE) ? children.getOrDefault(root, List.of()) : List.of(root);
        List<Integer> indices = targets.stream().flatMap(target -> byTarget.getOrDefault(target, List.of()).stream())
                .sorted().toList();
        int unfilteredTotal = indices.size();
        String needle = valueOrEmpty(query).trim().toLowerCase(Locale.ROOT);
        List<Map<String, Object>> matched = indices.stream().<Map<String, Object>>map(
                        index -> new LinkedHashMap<>(derivations.get(index)))
                .filter(item -> needle.isEmpty() || matchesText(item, needle)).toList();
        List<Map<String, Object>> items = matched.stream().skip(Math.max(0, offset)).limit(Math.max(0, limit)).toList();
        return result("total", matched.size(), "unfiltered_total", unfilteredTotal,
                "query", valueOrEmpty(query).trim(), "offset", offset, "items", items);
    }

    public List<LineageIssue> issues() {
        return List.copyOf(issues);
    }

    public List<List<String>> cycleComponents(String graphLevel) {
        return cycles.getOrDefault(graphLevel, List.of());
    }

    public BusinessIndex business() {
        return business;
    }

    List<Map<String, Object>> relationRecords() {
        return relations;
    }

    List<Map<String, Object>> definitionRecords() {
        return derivations;
    }

    List<Integer> edgeRelationIndexes(String graphLevel, String source, String target) {
        return outgoing(graphLevel).getOrDefault(source, Map.of()).getOrDefault(target, List.of());
    }

    List<String> childIds(String nodeId) {
        return children.getOrDefault(nodeId, List.of());
    }

    Set<String> nodeIds() {
        return Set.copyOf(nodes.keySet());
    }

    List<LineageDocument> sourceDocuments() {
        return sourceDocuments;
    }

    List<LineageIssue> initialIssues() {
        return initialIssues;
    }

    ValueLineageDecider valueLineageDecider() {
        return valueLineageDecider;
    }

    private void addDocument(LineageDocument document, Map<String, Set<String>> targetDocuments) {
        BusinessLine businessLine = document.businessLine(BusinessLine.EAST);
        for (int derivationIndex = 0; derivationIndex < document.derivations().size(); derivationIndex++) {
            Derivation derivation = document.derivations().get(derivationIndex);
            String originalTarget = columnId(derivation.target());
            String target = addColumn(derivation.target(), true);
            targetDocuments.computeIfAbsent(target, key -> new HashSet<>()).add(document.datasetId());
            if (needsSourceReview(derivation)) {
                requiredNode(target).unresolved = true;
            }
            List<LineageSource> projectedSources = derivation.sources().stream().map(this::projectSource).toList();
            Map<String, Object> definition = result("id", digest(document.datasetId(), derivationIndex),
                    "document", document.datasetId(), "index", derivationIndex, "target", target,
                    "script_name", document.scriptName(), "sources", projectedSources,
                    "transform_logic", derivation.transformLogic(), "metadata", derivation.metadata(),
                    "business_line", businessLine.name().toLowerCase(Locale.ROOT));
            if (projected) {
                definition.put("original_target", originalTarget);
                definition.put("original_sources", derivation.sources());
                definition.put("mapping_applied", mappingApplied(originalTarget, target));
            }
            byTarget.computeIfAbsent(target, key -> new ArrayList<>()).add(derivations.size());
            derivations.add(definition);
            for (int sourceIndex = 0; sourceIndex < derivation.sources().size(); sourceIndex++) {
                LineageSource source = derivation.sources().get(sourceIndex);
                String originalSource = source.kind() == SourceKind.COLUMN
                        ? columnId(source.ref()) : constantId(source.value());
                String sourceId = source.kind() == SourceKind.COLUMN
                        ? addColumn(source.ref(), false) : addConstant(source.value());
                String logicPath = resolveLogicPath(derivation.transformLogic(), source.path());
                Map<String, Object> relation = result("id", digest(document.datasetId(), derivationIndex, sourceIndex),
                        "source", sourceId, "target", target, "document", document.datasetId(),
                        "derivation_index", derivationIndex, "source_index", sourceIndex, "path", source.path(),
                        "logic_path", logicPath, "logic_inherited", logicPath != null && !logicPath.equals(source.path()),
                        "logic", logicPath == null ? Map.of() : derivation.transformLogic().get(logicPath),
                        "metadata", derivation.metadata(), "script_name", document.scriptName(),
                        "business_line", businessLine.name().toLowerCase(Locale.ROOT));
                if (projected) {
                    relation.put("original_source", originalSource);
                    relation.put("original_target", originalTarget);
                    relation.put("mapping_applied", mappingApplied(originalSource, sourceId)
                            || mappingApplied(originalTarget, target));
                }
                int relationIndex = relations.size();
                relations.add(relation);
                addEdge(COLUMN, sourceId, target, relationIndex);
                if (source.kind() == SourceKind.COLUMN) {
                    addEdge(TABLE, requiredNode(sourceId).parentId, requiredNode(target).parentId, relationIndex);
                }
            }
        }
    }

    private void finalizeSnapshot(Map<String, Set<String>> targetDocuments) {
        for (Map<String, Object> definition : derivations) {
            @SuppressWarnings("unchecked")
            List<LineageSource> sources = (List<LineageSource>) definition.get("sources");
            @SuppressWarnings("unchecked")
            Map<String, Map<String, Object>> logic = (Map<String, Map<String, Object>>) definition.get("transform_logic");
            if (sources.isEmpty() && !logic.isEmpty()) {
                String target = String.valueOf(definition.get("target"));
                Map<String, List<Integer>> neighbors = columnOutgoing.getOrDefault(target, Map.of());
                int downstreamCount = neighbors.size() - (neighbors.containsKey(target) ? 1 : 0);
                if (downstreamCount > 0) {
                    requiredNode(target).sourceReviewRequired = true;
                    requiredNode(requiredNode(target).parentId).sourceReviewRequired = true;
                }
                issues.add(emptySourceIssue(definition, target, downstreamCount));
            }
        }
        for (Node node : List.copyOf(nodes.values())) {
            if (!node.type.equals(COLUMN)) {
                continue;
            }
            children.computeIfAbsent(node.parentId, key -> new ArrayList<>()).add(node.id);
            if (node.unresolved) {
                requiredNode(node.parentId).unresolved = true;
            }
            if (!node.defined) {
                issues.add(LineageIssue.of("EXTERNAL_SOURCE", "来源字段未在已导入目标中定义。", null,
                        node.id, LineageIssue.Severity.INFO).with("target", node.qualifiedName));
            }
        }
        targetDocuments.forEach((target, documents) -> {
            if (documents.size() > 1) {
                issues.add(LineageIssue.of("CROSS_DOCUMENT_TARGET", "字段分布在多份文档。", null,
                        target, LineageIssue.Severity.INFO).with("documents", documents.stream().sorted().toList()));
            }
        });
        cycles.put(TABLE, findCycleComponents(tableOutgoing, tableIncoming));
        cycles.put(COLUMN, findCycleComponents(columnOutgoing, columnIncoming));
        for (Map.Entry<String, List<List<String>>> entry : cycles.entrySet()) {
            Set<String> multi = new HashSet<>();
            for (List<String> component : entry.getValue()) {
                if (component.size() > 1) {
                    multi.addAll(component);
                }
                component.forEach(id -> requiredNode(id).cyclic = true);
                issues.add(LineageIssue.of("CYCLE", entry.getKey() + " 图包含循环依赖。", null,
                                component.get(0), LineageIssue.Severity.WARNING)
                        .with("nodes", component).with("level", entry.getKey()));
            }
            multiNodeCycleMembers.put(entry.getKey(), multi);
        }
        Map<String, Set<String>> defined = new HashMap<>();
        Map<String, Set<String>> referenced = new HashMap<>();
        for (Map<String, Object> definition : derivations) {
            addLine(defined, String.valueOf(definition.get("target")), String.valueOf(definition.get("business_line")));
            addLine(defined, requiredNode(String.valueOf(definition.get("target"))).parentId,
                    String.valueOf(definition.get("business_line")));
        }
        for (Map<String, Object> relation : relations) {
            String source = String.valueOf(relation.get("source"));
            String line = String.valueOf(relation.get("business_line"));
            addLine(referenced, source, line);
            if (requiredNode(source).parentId != null) {
                addLine(referenced, requiredNode(source).parentId, line);
            }
        }
        nodes.values().forEach(node -> {
            node.definedLines.addAll(defined.getOrDefault(node.id, Set.of()).stream().sorted().toList());
            node.referencedLines.addAll(referenced.getOrDefault(node.id, Set.of()).stream().sorted().toList());
        });
    }

    private String addColumn(String reference, boolean defined) {
        String original = reference.toUpperCase(Locale.ROOT);
        ColumnProjection projection = columnProjector.project(original);
        String ref = projection.qualifiedName().toUpperCase(Locale.ROOT);
        int fieldSeparator = ref.lastIndexOf("::");
        String table = projection.parentTable().toUpperCase(Locale.ROOT);
        String column = ref.substring(fieldSeparator + 2);
        int namespaceSeparator = table.lastIndexOf('.');
        String namespace = namespaceSeparator < 0 ? "" : table.substring(0, namespaceSeparator);
        String tableName = namespaceSeparator < 0 ? table : table.substring(namespaceSeparator + 1);
        String tableId = "table:" + table;
        String columnId = "column:" + ref;
        Node tableNode = nodes.computeIfAbsent(tableId,
                key -> new Node(tableId, TABLE, tableName, table, namespace, null, null));
        Node columnNode = nodes.computeIfAbsent(columnId,
                key -> new Node(columnId, COLUMN, column, ref, namespace, tableId, null));
        if (defined) {
            tableNode.defined = true;
            columnNode.defined = true;
        }
        return columnId;
    }

    private LineageSource projectSource(LineageSource source) {
        if (!projected || source.kind() != SourceKind.COLUMN) {
            return source;
        }
        ColumnProjection projection = columnProjector.project(source.ref().toUpperCase(Locale.ROOT));
        return LineageSource.column(projection.qualifiedName(), source.path());
    }

    private boolean mappingApplied(String originalId, String projectedId) {
        if (!originalId.equals(projectedId)) {
            return true;
        }
        if (!projectedId.startsWith("column:")) {
            return false;
        }
        String original = originalId.substring("column:".length());
        String originalTable = original.substring(0, original.lastIndexOf("::"));
        return !requiredNode(projectedId).parentId.equals("table:" + originalTable);
    }

    private String addConstant(String value) {
        String id = constantId(value);
        nodes.computeIfAbsent(id, key -> new Node(id, CONSTANT, value == null || value.isEmpty() ? "''" : value,
                "CONST:" + value, "CONST", null, value));
        nodes.get(id).defined = true;
        return id;
    }

    private static String columnId(String reference) {
        return "column:" + reference.toUpperCase(Locale.ROOT);
    }

    private String constantId(String value) {
        return "constant:" + digest(value);
    }

    private void addEdge(String graphLevel, String source, String target, int relationIndex) {
        Map<String, Map<String, List<Integer>>> outgoing = outgoing(graphLevel);
        Map<String, Map<String, List<Integer>>> incoming = incoming(graphLevel);
        List<Integer> evidence = outgoing.computeIfAbsent(source, key -> new HashMap<>())
                .computeIfAbsent(target, key -> new ArrayList<>());
        evidence.add(relationIndex);
        incoming.computeIfAbsent(target, key -> new HashMap<>()).put(source, evidence);
    }

    private Traversal walk(String root, String direction, int depth, int maxNodes,
                           boolean includeConstants, EdgePredicate edgeAllowed) {
        Map<String, Map<String, List<Integer>>> adjacency = direction.equals("upstream")
                ? incoming(level(root)) : outgoing(level(root));
        Map<String, Integer> distance = new LinkedHashMap<>();
        Map<String, String> parent = new HashMap<>();
        Set<String> reasons = new TreeSet<>();
        Set<String> boundary = new TreeSet<>();
        Deque<String> queue = new ArrayDeque<>();
        distance.put(root, 0);
        queue.add(root);
        while (!queue.isEmpty()) {
            String current = queue.removeFirst();
            for (String neighbor : new TreeSet<>(adjacency.getOrDefault(current, Map.of()).keySet())) {
                String source = direction.equals("upstream") ? neighbor : current;
                String target = direction.equals("upstream") ? current : neighbor;
                if (edgeAllowed != null && !edgeAllowed.test(source, target, direction)) {
                    continue;
                }
                if (!includeConstants && requiredNode(neighbor).type.equals(CONSTANT)) {
                    continue;
                }
                if (distance.containsKey(neighbor)) {
                    continue;
                }
                if (distance.get(current) >= depth) {
                    reasons.add("depth_limit");
                    boundary.add(current);
                    continue;
                }
                if (distance.size() >= maxNodes) {
                    reasons.add("node_limit");
                    boundary.add(current);
                    continue;
                }
                distance.put(neighbor, distance.get(current) + 1);
                parent.put(neighbor, current);
                queue.addLast(neighbor);
            }
        }
        return new Traversal(distance, parent, reasons, boundary);
    }

    private List<List<String>> findCycleComponents(Map<String, Map<String, List<Integer>>> outgoing,
                                                   Map<String, Map<String, List<Integer>>> incoming) {
        Set<String> vertices = new TreeSet<>(outgoing.keySet());
        vertices.addAll(incoming.keySet());
        Set<String> seen = new HashSet<>();
        List<String> order = new ArrayList<>();
        for (String root : vertices) {
            if (!seen.add(root)) {
                continue;
            }
            Deque<DepthFrame> stack = new ArrayDeque<>();
            stack.push(new DepthFrame(root, sortedNeighbors(outgoing, root)));
            while (!stack.isEmpty()) {
                DepthFrame frame = stack.peek();
                if (frame.position >= frame.neighbors.size()) {
                    order.add(frame.node);
                    stack.pop();
                    continue;
                }
                String child = frame.neighbors.get(frame.position++);
                if (seen.add(child)) {
                    stack.push(new DepthFrame(child, sortedNeighbors(outgoing, child)));
                }
            }
        }
        Set<String> assigned = new HashSet<>();
        List<List<String>> components = new ArrayList<>();
        Collections.reverse(order);
        for (String root : order) {
            if (!assigned.add(root)) {
                continue;
            }
            List<String> component = new ArrayList<>();
            Deque<String> stack = new ArrayDeque<>();
            stack.push(root);
            while (!stack.isEmpty()) {
                String current = stack.pop();
                component.add(current);
                for (String parent : incoming.getOrDefault(current, Map.of()).keySet()) {
                    if (assigned.add(parent)) {
                        stack.push(parent);
                    }
                }
            }
            if (component.size() > 1 || outgoing.getOrDefault(root, Map.of()).containsKey(root)) {
                Collections.sort(component);
                components.add(component);
            }
        }
        return List.copyOf(components);
    }

    private List<String> sortedNeighbors(Map<String, Map<String, List<Integer>>> adjacency, String node) {
        return adjacency.getOrDefault(node, Map.of()).keySet().stream().sorted().toList();
    }

    private Map<String, Object> evidenceItem(int index) {
        Map<String, Object> item = new LinkedHashMap<>(relations.get(index));
        item.put("source_display_name", requiredNode(String.valueOf(item.get("source"))).qualifiedName);
        item.put("target_display_name", requiredNode(String.valueOf(item.get("target"))).qualifiedName);
        return item;
    }

    private boolean matchesText(Object value, String query) {
        try {
            return JSON.writeValueAsString(value).toLowerCase(Locale.ROOT).contains(query);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cannot serialize graph record", exception);
        }
    }

    private Map<String, Object> edge(String graphLevel, String source, String target) {
        return result("id", digest(source, target), "source", source, "target", target,
                "evidence_count", outgoing(graphLevel).get(source).get(target).size());
    }

    private Map<String, Map<String, List<Integer>>> outgoing(String graphLevel) {
        return graphLevel.equals(TABLE) ? tableOutgoing : columnOutgoing;
    }

    private Map<String, Map<String, List<Integer>>> incoming(String graphLevel) {
        return graphLevel.equals(TABLE) ? tableIncoming : columnIncoming;
    }

    private String level(String nodeId) {
        return requiredNode(nodeId).type.equals(TABLE) ? TABLE : COLUMN;
    }

    private Node requiredNode(String nodeId) {
        Node node = nodes.get(nodeId);
        if (node == null) {
            throw new IllegalArgumentException("Unknown lineage node: " + nodeId);
        }
        return node;
    }

    private LineageIssue emptySourceIssue(Map<String, Object> definition, String target, int downstreamCount) {
        boolean used = downstreamCount > 0;
        return LineageIssue.of(used ? "EMPTY_SOURCES_USED" : "EMPTY_SOURCES",
                        used ? "字段有加工逻辑但没有来源，且已被其他字段引用。" : "字段有加工逻辑但没有来源。",
                        String.valueOf(definition.get("document")), "/derivations/" + definition.get("index"),
                        used ? LineageIssue.Severity.WARNING : LineageIssue.Severity.INFO)
                .with("target", requiredNode(target).qualifiedName).with("downstream_count", downstreamCount);
    }

    private boolean needsSourceReview(Derivation derivation) {
        return derivation.sources().isEmpty() && !derivation.transformLogic().isEmpty();
    }

    private String resolveLogicPath(Map<String, Map<String, Object>> logic, String path) {
        String current = path;
        while (current != null) {
            if (logic.containsKey(current)) {
                return current;
            }
            int separator = current.lastIndexOf('-');
            current = separator < 0 ? null : current.substring(0, separator);
        }
        return null;
    }

    private int minimumDistance(Map<String, Object> item) {
        return Arrays.asList(item.get("upstream_distance"), item.get("downstream_distance")).stream()
                .filter(Objects::nonNull).mapToInt(value -> (Integer) value).min().orElse(Integer.MAX_VALUE);
    }

    private int edgeCount(Map<String, Map<String, List<Integer>>> adjacency) {
        return adjacency.values().stream().mapToInt(Map::size).sum();
    }

    private void addLine(Map<String, Set<String>> index, String node, String line) {
        index.computeIfAbsent(node, key -> new HashSet<>()).add(line);
    }

    private String digest(Object... values) {
        String tuple = Arrays.stream(values).map(this::jsonValue).collect(Collectors.joining(", ", "[", "]"));
        return DigestUtils.sha256(tuple);
    }

    private String jsonValue(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cannot create stable graph identifier", exception);
        }
    }

    private String valueOrEmpty(String value) {
        return value == null ? "" : value;
    }

    private static Map<String, Object> result(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            result.put(String.valueOf(values[index]), values[index + 1]);
        }
        return result;
    }

    private static final class Node {
        private final String id;
        private final String type;
        private final String name;
        private final String qualifiedName;
        private final String namespace;
        private final String parentId;
        private final String value;
        private final List<String> definedLines = new ArrayList<>();
        private final List<String> referencedLines = new ArrayList<>();
        private boolean defined;
        private boolean unresolved;
        private boolean sourceReviewRequired;
        private boolean cyclic;

        private Node(String id, String type, String name, String qualifiedName,
                     String namespace, String parentId, String value) {
            this.id = id;
            this.type = type;
            this.name = name;
            this.qualifiedName = qualifiedName;
            this.namespace = namespace;
            this.parentId = parentId;
            this.value = value;
        }

        private Map<String, Object> toMap() {
            Map<String, Object> map = result("id", id, "type", type, "name", name,
                    "qualified_name", qualifiedName, "namespace", namespace, "parent_id", parentId,
                    "defined", defined, "unresolved", unresolved,
                    "source_review_required", sourceReviewRequired, "cyclic", cyclic,
                    "defined_lines", List.copyOf(definedLines), "referenced_lines", List.copyOf(referencedLines));
            if (type.equals(CONSTANT)) {
                map.put("value", value);
            }
            return map;
        }
    }

    private static final class DepthFrame {
        private final String node;
        private final List<String> neighbors;
        private int position;

        private DepthFrame(String node, List<String> neighbors) {
            this.node = node;
            this.neighbors = neighbors;
        }
    }

    private record Traversal(Map<String, Integer> distance, Map<String, String> parent,
                             Set<String> reasons, Set<String> boundary) {
    }

    private record EdgePair(String source, String target) implements Comparable<EdgePair> {
        @Override
        public int compareTo(EdgePair other) {
            int sourceOrder = source.compareTo(other.source);
            return sourceOrder == 0 ? target.compareTo(other.target) : sourceOrder;
        }
    }

    private record FrontierEdge(String node, EdgePair pair) {
    }
}
