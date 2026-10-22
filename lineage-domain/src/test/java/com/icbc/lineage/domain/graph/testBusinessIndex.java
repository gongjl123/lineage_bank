package com.icbc.lineage.domain.graph;

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

public class testBusinessIndex {

    @Test
    public void catalogsDirectInheritedAndCandidateBusinessLabels() {
        LineageGraph graph = graph();
        BusinessIndex business = graph.business();

        assertEquals(3, business.catalog("", 0, 20, "").get("total"));
        assertEquals(1, business.catalog("票据", 0, 20, "").get("total"));
        assertEquals(0, business.catalog("missing", 0, 20, "").get("total"));
        assertEquals(0, ((List<?>) business.catalog("", 5, 20, "").get("items")).size());
        Map<String, Object> summary = business.summary("票据垫款", "");
        assertEquals(1, summary.get("field_count"));
        assertEquals(2, summary.get("branch_count"));
        assertTrue(business.matches("票据垫款", "column:BANK.T::X"));
        assertTrue(business.matches("票据垫款", "table:BANK.T"));
        assertFalse(business.matches("票据垫款", "table:BANK.A"));

        List<Map<String, Object>> branches = business.records("票据垫款", "column:BANK.T::X");
        assertEquals(2, branches.size());
        assertTrue(branches.stream().anyMatch(item -> Boolean.TRUE.equals(item.get("business_tag_inherited"))));
        Map<String, Object> branchList = business.branchList("票据垫款", "table:BANK.T", 0, 1);
        assertEquals(2, branchList.get("total"));
        assertEquals(1, ((List<?>) branchList.get("items")).size());
        assertTrue(((Number) business.nodeInfo("票据垫款", "table:BANK.T").get("business_field_count")).intValue() > 0);
    }

    @Test
    public void reportsBusinessEvidenceAndAnnotatesExistingEdges() {
        LineageGraph graph = graph();
        BusinessIndex business = graph.business();
        String source = "column:BANK.A::X";
        String target = "column:BANK.T::X";

        assertFalse(business.relationBusinessInfo(0).isEmpty());
        assertTrue(business.relationBusinessInfo(100).isEmpty());
        assertEquals(2, business.relationIndexes("票据垫款", target).size());
        assertEquals(2, business.edgeBusinessCount("column", source, target, "票据垫款", ""));
        assertTrue(business.edgeHasBusiness("column", source, target, "票据垫款"));
        assertEquals(2, business.edgeBusinessBreakdown("column", source, target, "票据垫款", "")
                .get("business_direct_count"));

        Map<String, Object> graphData = graph.subgraph(target, "upstream", 2, 20, false, 20, null);
        assertEquals(graphData, business.annotate(graphData, ""));
        Map<String, Object> annotated = business.annotate(graphData, "票据垫款");
        assertEquals("票据垫款", annotated.get("business_tag"));
        assertTrue(((List<?>) annotated.get("edges")).stream().map(item -> (Map<?, ?>) item)
                .anyMatch(item -> ((Number) item.get("business_match_count")).intValue() > 0));

        Map<String, Object> diagnostics = business.diagnostics("table:BANK.T", 0, 20);
        assertTrue(((Number) diagnostics.get("total")).intValue() >= 4);
        assertTrue(((Map<?, ?>) diagnostics.get("status_counts")).containsKey("candidate"));
        assertFalse(((List<?>) business.diagnostics(target, 1, 1).get("items")).isEmpty());
    }

    @Test
    public void restrictsOnlyBusinessEntryEdgesAndKeepsLineageContext() {
        LineageGraph graph = graph();
        BusinessIndex business = graph.business();
        String root = "column:BANK.T::X";

        Map<String, Object> upstream = business.fieldUpstream(root, "票据垫款", 3, 20, false, 20);
        assertEquals("upstream", upstream.get("direction"));
        assertEquals("resolved", upstream.get("business_certainty"));
        assertTrue(((List<?>) upstream.get("edges")).stream().map(item -> (Map<?, ?>) item)
                .allMatch(item -> ((Number) item.get("business_match_count")).intValue() > 0));

        Map<String, Object> both = business.fieldLineage(root, "票据垫款", "both", 3, 20, false, 20);
        assertTrue(((List<?>) both.get("nodes")).stream().map(item -> (Map<?, ?>) item)
                .anyMatch(item -> "column:BANK.U::X".equals(item.get("id"))));
        Map<String, Object> upstreamCycle = business.fieldUpstream(root, "票据垫款", 10, 20, false, 20);
        assertFalse(((List<?>) upstreamCycle.get("nodes")).stream().map(item -> (Map<?, ?>) item)
                .anyMatch(item -> "column:BANK.U::X".equals(item.get("id"))));
        Map<String, Object> downstreamCycle = business.fieldLineage(root, "票据垫款", "downstream",
                3, 20, false, 20);
        assertTrue(((List<?>) downstreamCycle.get("edges")).stream().map(item -> (Map<?, ?>) item)
                .anyMatch(item -> "column:BANK.U::X".equals(item.get("source"))
                        && root.equals(item.get("target"))));
        assertTrue(((List<?>) downstreamCycle.get("edges")).stream().map(item -> (Map<?, ?>) item)
                .allMatch(item -> ((Number) item.get("business_match_count")).intValue() == 0));
        Map<String, Object> candidate = business.fieldLineage("column:BANK.T::Z", "保函", "upstream",
                3, 20, false, 20);
        assertEquals(true, candidate.get("candidate_business"));
        assertThrows(IllegalArgumentException.class,
                () -> business.fieldLineage("table:BANK.T", "票据垫款", "upstream", 2, 10, false, 10));
        assertThrows(IllegalArgumentException.class,
                () -> business.fieldLineage("column:BANK.T::X", "missing", "upstream", 2, 10, false, 10));
    }

    @Test
    public void normalizesBusinessNamesByLine() {
        assertEquals("单位贷款", BusinessIndex.businessName("'单位贷款' AS YWGSBM", "std"));
        assertEquals("原始表达式", BusinessIndex.businessName("原始表达式", "east"));
        assertEquals("", BusinessIndex.businessName("T.YWGSBM", "east"));
        assertEquals("", BusinessIndex.businessName("CASE WHEN X=1 THEN '贷款' END", "std"));
        assertEquals("", BusinessIndex.businessName("CONST: NULL", "east"));
        assertEquals("", BusinessIndex.businessName(1, "east"));
    }

    private LineageGraph graph() {
        Derivation labelled = new Derivation("BANK.T::X", List.of(
                LineageSource.column("BANK.A::X", "path-1"),
                LineageSource.column("BANK.A::X", "path-1-1"),
                LineageSource.column("BANK.U::X", "cycle")), Map.of(
                "path-1", Map.of("YWGSBM", "'票据垫款' AS YWGSBM", "expression", "A.X"),
                "path-1-1", Map.of("expression", "A.X")), Map.of());
        Derivation candidate = new Derivation("BANK.T::Z",
                List.of(LineageSource.column("BANK.A::Z", "case")), Map.of(
                "case", Map.of("YWGSBM", "CASE WHEN A.Z='1' THEN '保函' ELSE '贷款' END",
                        "expression", "A.Z")), Map.of());
        Derivation technical = new Derivation("BANK.T::TECH",
                List.of(LineageSource.column("BANK.A::TECH", "technical")), Map.of(
                "technical", Map.of("BIZ_LABEL", "STD_ROUTE", "expression", "A.TECH")), Map.of());
        Derivation unknown = new Derivation("BANK.T::UNKNOWN",
                List.of(LineageSource.column("BANK.A::UNKNOWN", "unknown")), Map.of(
                "unknown", Map.of("YWGSBM", 1, "expression", "A.UNKNOWN")), Map.of());
        Derivation downstream = new Derivation("BANK.U::X",
                List.of(LineageSource.column("BANK.T::X", "select")),
                Map.of("select", Map.of("expression", "T.X")), Map.of());
        LineageDocument document = new LineageDocument("1.0", "business", "business.sql",
                List.of(labelled, candidate, technical, unknown, downstream), Map.of("business_line", "east"));
        return new LineageGraph(List.of(document), List.of());
    }
}
