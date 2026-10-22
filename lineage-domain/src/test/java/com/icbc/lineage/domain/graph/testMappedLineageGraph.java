package com.icbc.lineage.domain.graph;

import com.icbc.lineage.domain.catalog.NameDictionary;
import com.icbc.lineage.domain.catalog.TableNameMapper;
import com.icbc.lineage.domain.model.Derivation;
import com.icbc.lineage.domain.model.LineageDocument;
import com.icbc.lineage.domain.model.LineageSource;
import org.junit.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class testMappedLineageGraph {

    @Test
    public void groupsMappedTablesAndKeepsMissingFieldsPending() {
        MappedLineageGraph graph = graph();

        assertEquals("table:BUS.B", graph.resolve("table:SRC.A"));
        assertEquals("column:BUS.B::F", graph.resolve("column:SRC.C::F"));
        Map<String, Object> table = graph.node("table:SRC.A");
        assertEquals(List.of("BUS.B", "SRC.A", "SRC.C"), table.get("original_names"));
        assertEquals(3, table.get("mapping_member_count"));
        assertEquals(2L, table.get("pending_field_count"));
        Map<String, Object> field = graph.node("column:SRC.A::F");
        assertEquals("BUS.B::F", field.get("display_qualified_name"));

        Map<String, Object> pending = graph.node("column:SRC.A::MISSING");
        assertEquals("table:BUS.B", pending.get("parent_id"));
        assertEquals(true, pending.get("mapping_pending"));
        assertEquals("field_missing", pending.get("mapping_status"));
        assertThrows(IllegalArgumentException.class, () -> graph.resolve("column:BUS.B::MISSING"));

        Map<String, Object> status = graph.mappingStatus("missing", 0, 20, null);
        assertEquals(1, status.get("total"));
        assertEquals(0, graph.mappingStatus("missing", 1, 20,
                Set.of("column:SRC.A::MISSING")).get("items") instanceof List<?> rows ? rows.size() : -1);
        assertEquals(2, graph.mappingSummary().get("pending_fields"));
        assertEquals(2, graph.mappingStatus("", 0, 20, null).get("total"));
        assertFalse(graph.issues().isEmpty());
        assertTrue(graph.business() != null && graph.projectedGraph() != null);
    }

    @Test
    public void searchesAliasesAndUsesOneMappedProjectionForAllQueries() {
        MappedLineageGraph graph = graph();

        assertEquals(1, graph.search("SRC.C", "table", "", "all", "", 0, 20, "", null).get("total"));
        assertEquals(3, graph.search("BUS.B", "column", "", "all", "table:SRC.A",
                0, 20, "", null).get("total"));
        assertEquals(1, graph.search("BUS.B", "column", "", "all", "table:SRC.A",
                0, 20, "", Set.of("column:SRC.A::F")).get("total"));
        Map<String, Object> stats = graph.stats();
        assertEquals("mapped", stats.get("lineage_view"));
        assertTrue(((Number) ((Map<?, ?>) stats.get("original_counts")).get("tables")).intValue()
                > ((Number) stats.get("tables")).intValue());

        Map<String, Object> subgraph = graph.subgraph("column:SRC.A::F", "both", 2,
                30, true, 30, null);
        assertEquals("column:BUS.B::F", subgraph.get("root"));
        assertEquals("mapped", subgraph.get("lineage_view"));
        assertTrue(((List<?>) subgraph.get("edges")).stream().map(item -> (Map<?, ?>) item)
                .anyMatch(item -> ((Number) item.get("mapping_evidence_count")).intValue() > 0));
        assertTrue(((Number) graph.impact("column:SRC.A::F", 5, 50).get("total")).intValue() > 0);
        assertTrue(((Number) graph.associatedTables("table:SRC.A", 50).get("total")).intValue() > 0);
        assertEquals(List.of("table:LAKE.RAW", "table:BUS.B", "table:REPORT.OUT"),
                graph.tablePath("table:LAKE.RAW", "table:REPORT.OUT", "downstream", 50).get("path"));
    }

    @Test
    public void retainsOriginalEvidenceDefinitionsAndMappingBasis() {
        MappedLineageGraph graph = graph();

        Map<String, Object> evidence = graph.evidence("column:REPORT.OUT::F", "column:SRC.A::F",
                "column:REPORT.OUT::F", 0, 20, "");
        assertEquals(2, evidence.get("total"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) evidence.get("items");
        assertEquals(Set.of("column:SRC.A::F", "column:SRC.C::F"),
                items.stream().map(item -> String.valueOf(item.get("original_source"))).collect(java.util.stream.Collectors.toSet()));
        assertTrue(items.stream().allMatch(item -> !((Map<?, ?>) item.get("mapping_basis")).isEmpty()));

        Map<String, Object> definitions = graph.definitions("column:SRC.A::F", 0, 20, "");
        assertEquals(2, definitions.get("total"));
        assertTrue(((List<?>) definitions.get("items")).stream().map(item -> (Map<?, ?>) item)
                .anyMatch(item -> "column:SRC.A::F".equals(item.get("original_target"))));
        assertEquals(0, graph.evidence("column:REPORT.OUT::F", "", "", 0, 20, "not-found").get("total"));
    }

    private MappedLineageGraph graph() {
        LineageDocument document = new LineageDocument("1.0", "mapping", "mapping.sql", List.of(
                derivation("BUS.B::F", List.of(LineageSource.column("LAKE.RAW::F", "raw"))),
                derivation("SRC.A::F", List.of(LineageSource.column("SRC.UP::F", "up"))),
                derivation("REPORT.OUT::F", List.of(
                        LineageSource.column("SRC.A::F", "a"),
                        LineageSource.column("SRC.C::F", "c"),
                        LineageSource.constant("1", "constant"))),
                derivation("REPORT.OUT::MISSING", List.of(
                        LineageSource.column("SRC.A::MISSING", "missing"))),
                derivation("REPORT.OUT::OTHER", List.of(
                        LineageSource.column("SRC.C::OTHER", "other")))
        ), Map.of("business_line", "east"));
        LineageGraph raw = new LineageGraph(List.of(document), List.of());
        TableNameMapper mapper = new TableNameMapper(
                Map.of("SRC.A", "BUS.B", "SRC.C", "BUS.B"),
                Map.of("SRC.A", List.of("EAST湖表映射"), "SRC.C", List.of("EAST湖表映射")),
                "mapping.xlsx", "fixture", "database", "2026-10-22T00:00:00Z");
        return new MappedLineageGraph(raw, mapper, NameDictionary.empty());
    }

    private Derivation derivation(String target, List<LineageSource> sources) {
        return new Derivation(target, sources, Map.of(), Map.of());
    }
}
