package com.icbc.lineage.domain.graph;

import com.icbc.lineage.domain.model.BusinessLine;
import com.icbc.lineage.domain.model.Derivation;
import com.icbc.lineage.domain.model.LineageDocument;
import com.icbc.lineage.domain.model.LineageIssue;
import com.icbc.lineage.domain.model.LineageSource;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class testLineageGraph {

    private static final String TABLE_A = "table:BANK.A";
    private static final String TABLE_B = "table:BANK.B";
    private static final String TABLE_C = "table:BANK.C";
    private static final String COLUMN_A_ID = "column:BANK.A::ID";
    private static final String COLUMN_B_ID = "column:BANK.B::ID";
    private static final String COLUMN_C_ID = "column:BANK.C::ID";

    @Test
    public void buildsSnapshotAndExposesCatalogStatistics() {
        LineageIssue retained = LineageIssue.of("BASE", "base", "doc", "", LineageIssue.Severity.INFO);
        LineageIssue stale = LineageIssue.of("EMPTY_SOURCES", "stale", "doc", "", LineageIssue.Severity.INFO);
        LineageGraph graph = new LineageGraph(documents(), List.of(retained, stale));

        assertEquals(COLUMN_A_ID, graph.resolve(COLUMN_A_ID));
        assertThrows(IllegalArgumentException.class, () -> graph.resolve("column:MISSING::ID"));
        Map<String, Object> stats = graph.stats();
        assertEquals(6, stats.get("documents"));
        assertEquals(10L, stats.get("columns"));
        assertEquals(1L, stats.get("constants"));
        assertEquals(1L, stats.get("external_columns"));
        assertTrue(((Number) stats.get("relations")).intValue() >= 9);
        assertTrue(((List<?>) stats.get("namespaces")).contains("BANK"));
        assertTrue(((Map<?, ?>) stats.get("issue_counts")).containsKey("EXTERNAL_SOURCE"));
        assertTrue(((Map<?, ?>) stats.get("issue_counts")).containsKey("EMPTY_SOURCES_USED"));
        assertTrue(graph.issues().stream().anyMatch(item -> item.code().equals("BASE")));
        assertFalse(graph.issues().stream().anyMatch(item -> item.message().equals("stale")));

        Map<String, Object> node = graph.node(COLUMN_A_ID);
        assertEquals("column", node.get("type"));
        assertEquals(TABLE_A, node.get("parent_id"));
        assertEquals(List.of("east"), node.get("defined_lines"));
        assertTrue(((Number) node.get("downstream_count")).intValue() > 0);
        assertEquals(4, graph.node(TABLE_A).get("field_count"));
        assertEquals(1, graph.node("column:BANK.A::SELF").get("self_reference_count"));
        assertTrue((Boolean) graph.node("column:BANK.X::ID").get("multi_node_cycle"));
        assertFalse(graph.cycleComponents("column").isEmpty());
        assertTrue(graph.cycleComponents("missing").isEmpty());
    }

    @Test
    public void searchesAndTraversesAtColumnAndTableLevel() {
        LineageGraph graph = new LineageGraph(documents(), List.of());

        Map<String, Object> search = graph.search("ID", "column", "BANK", "defined", "", 0, 20,
                BusinessLine.EAST.name().toLowerCase(), null);
        assertTrue(((Number) search.get("total")).intValue() >= 5);
        Map<String, Object> children = graph.search("", "all", "", "all", TABLE_A, 0, 10, "",
                Set.of(COLUMN_A_ID));
        assertEquals(1, children.get("total"));

        Map<String, Object> columns = graph.subgraph(COLUMN_B_ID, "both", 1, 20,
                false, 20, null);
        assertEquals("column", columns.get("level"));
        assertEquals(3, ((List<?>) columns.get("nodes")).size());
        assertTrue((Boolean) columns.get("truncated"));
        assertTrue(((List<?>) columns.get("reasons")).contains("depth_limit"));

        Map<String, Object> constants = graph.subgraph(COLUMN_A_ID, "upstream", 1, 20,
                true, 20, null);
        assertTrue(((List<?>) constants.get("nodes")).stream()
                .map(item -> (Map<?, ?>) item).anyMatch(item -> item.get("type").equals("constant")));

        Map<String, Object> filtered = graph.subgraph(COLUMN_B_ID, "upstream", 5, 20,
                false, 20, (source, target) -> !source.equals(COLUMN_A_ID));
        assertEquals(1, ((List<?>) filtered.get("nodes")).size());

        Map<String, Object> limited = graph.subgraph(TABLE_A, "downstream", 5, 2,
                false, 0, null);
        assertTrue(((List<?>) limited.get("reasons")).contains("node_limit"));
        assertTrue(((List<?>) limited.get("reasons")).contains("edge_limit"));
        assertTrue((Boolean) limited.get("table_self_edges_hidden"));
    }

    @Test
    public void reportsImpactAssociationsAndShortestPaths() {
        LineageGraph graph = new LineageGraph(documents(), List.of());

        Map<String, Object> impact = graph.impact(TABLE_A, 10, 100);
        assertEquals(2, impact.get("total"));
        assertEquals(1L, impact.get("direct"));
        assertEquals(1L, impact.get("indirect"));
        assertEquals(List.of(TABLE_B, TABLE_C), impact.get("affected_tables"));
        assertTrue((Boolean) impact.get("approximation"));

        Map<String, Object> associations = graph.associatedTables(TABLE_B, 100);
        assertEquals(3, associations.get("total"));
        assertEquals(true, associations.get("excludes_root"));
        assertEquals(1L, ((Map<?, ?>) ((Map<?, ?>) associations.get("summary")).get("upstream")).get("direct"));

        Map<String, Object> downstream = graph.tablePath(TABLE_A, TABLE_C, "downstream", 100);
        assertEquals(List.of(TABLE_A, TABLE_B, TABLE_C), downstream.get("path"));
        assertEquals(2, downstream.get("depth"));
        Map<String, Object> upstream = graph.tablePath(TABLE_C, TABLE_A, "upstream", 100);
        assertEquals(List.of(TABLE_A, TABLE_B, TABLE_C), upstream.get("path"));
        assertThrows(IllegalArgumentException.class,
                () -> graph.tablePath(COLUMN_A_ID, COLUMN_C_ID, "downstream", 100));
        assertThrows(IllegalArgumentException.class,
                () -> graph.tablePath(TABLE_A, TABLE_A, "downstream", 100));
        assertThrows(IllegalArgumentException.class,
                () -> graph.tablePath(TABLE_A, "table:BANK.X", "downstream", 100));
    }

    @Test
    public void filtersEvidenceDefinitionsAndValueEdges() {
        LineageGraph graph = new LineageGraph(documents(), List.of(),
                relation -> !"helper".equals(relation.get("path")));

        assertTrue(graph.valueEdge(COLUMN_A_ID, COLUMN_B_ID));
        assertTrue(graph.valueEdge(COLUMN_A_ID, COLUMN_B_ID));
        assertFalse(graph.valueEdge("column:BANK.A::REVIEW", "column:BANK.B::REVIEW"));
        assertFalse(graph.valueEdge(COLUMN_C_ID, COLUMN_A_ID));
        assertTrue(new LineageGraph(documents(), List.of()).valueEdge(COLUMN_A_ID, COLUMN_B_ID));

        Map<String, Object> tableEvidence = graph.evidence(TABLE_A, "", "", 0, 20, "doc-a");
        assertTrue(((Number) tableEvidence.get("unfiltered_total")).intValue() >
                ((Number) tableEvidence.get("total")).intValue());
        assertTrue(((Number) tableEvidence.get("internal")).longValue() > 0);
        Map<String, Object> exact = graph.evidence(COLUMN_A_ID, COLUMN_A_ID, COLUMN_B_ID,
                0, 1, "");
        assertEquals(1, exact.get("total"));
        assertNotNull(((Map<?, ?>) ((List<?>) exact.get("items")).get(0)).get("source_display_name"));

        Map<String, Object> tableDefinitions = graph.definitions(TABLE_A, 0, 20, "script-a");
        assertEquals(5, tableDefinitions.get("unfiltered_total"));
        assertEquals(4, tableDefinitions.get("total"));
        Map<String, Object> columnDefinitions = graph.definitions(COLUMN_A_ID, 0, 1, "");
        assertEquals(1, columnDefinitions.get("total"));
    }

    private List<LineageDocument> documents() {
        List<LineageDocument> documents = new ArrayList<>();
        documents.add(document("doc-a", "script-a.sql", List.of(
                derivation("BANK.A::ID", List.of(
                        LineageSource.column("BANK.EXT::ID", "select-0"),
                        LineageSource.constant("1", "select-1")), Map.of(
                        "select", Map.of("expression", "EXT.ID"),
                        "select-1", Map.of("expression", "1"))),
                derivation("BANK.A::SELF", List.of(LineageSource.column("BANK.A::SELF", "select")),
                        Map.of("select", Map.of("expression", "SELF"))),
                derivation("BANK.A::REVIEW", List.of(), Map.of("select", Map.of("expression", "CASE"))),
                derivation("BANK.A::CROSS", List.of(LineageSource.column("BANK.A::ID", "select")),
                        Map.of("select", Map.of("expression", "ID"))))));
        documents.add(document("doc-b", "script-b.sql", List.of(
                derivation("BANK.B::ID", List.of(LineageSource.column("BANK.A::ID", "select")),
                        Map.of("select", Map.of("expression", "A.ID"))),
                derivation("BANK.B::REVIEW", List.of(LineageSource.column("BANK.A::REVIEW", "helper")),
                        Map.of("helper", Map.of("expression", "A.REVIEW"))))));
        documents.add(document("doc-c", "script-c.sql", List.of(
                derivation("BANK.C::ID", List.of(LineageSource.column("BANK.B::ID", "select")),
                        Map.of("select", Map.of("expression", "B.ID"))))));
        documents.add(document("doc-x", "script-x.sql", List.of(
                derivation("BANK.X::ID", List.of(LineageSource.column("BANK.Y::ID", "select")),
                        Map.of("select", Map.of("expression", "Y.ID"))))));
        documents.add(document("doc-y", "script-y.sql", List.of(
                derivation("BANK.Y::ID", List.of(LineageSource.column("BANK.X::ID", "select")),
                        Map.of("select", Map.of("expression", "X.ID"))))));
        documents.add(document("doc-cross", "script-cross.sql", List.of(
                derivation("BANK.A::CROSS", List.of(LineageSource.column("BANK.A::ID", "select")),
                        Map.of("select", Map.of("expression", "ID"))))));
        return documents;
    }

    private LineageDocument document(String id, String script, List<Derivation> derivations) {
        return new LineageDocument("1.0", id, script, derivations, Map.of("business_line", "east"));
    }

    private Derivation derivation(String target, List<LineageSource> sources,
                                  Map<String, Map<String, Object>> logic) {
        return new Derivation(target, sources, logic, Map.of("owner", "test"));
    }
}
