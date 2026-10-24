package com.icbc.lineage.infrastructure.sql;

import org.junit.Test;
import com.icbc.lineage.domain.graph.LineageGraph;
import com.icbc.lineage.domain.model.Derivation;
import com.icbc.lineage.domain.model.LineageDocument;
import com.icbc.lineage.domain.model.LineageSource;
import com.icbc.lineage.domain.graph.ValueLineageDecision;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class testDruidValueLineageDecider {

    private final DruidValueLineageDecider decider = new DruidValueLineageDecider();

    @Test
    public void classifiesSelectProjectionAndConservativeFallbacks() {
        assertTrue(decider.logicSuppliesTargetValue("YBT.REPORT::JRXKZH", logic(
                "T.A010003", "SELECT T.A010003 AS JRXKZH FROM YBT.MAIN T")));
        assertFalse(decider.logicSuppliesTargetValue("YBT.REPORT::JRXKZH", logic(
                "P.J030002", "SELECT P.J030002 AS DBXYID FROM YBT.HELPER P")));
        assertTrue(decider.logicSuppliesTargetValue("S.T::X", Map.of()));
        assertTrue(decider.logicSuppliesTargetValue("", logic("A.Y", "not valid sql (")));
        assertTrue(decider.logicSuppliesTargetValue("S.T::X", logic("A.Y", "SELECT * FROM S.A")));
        assertTrue(decider.logicSuppliesTargetValue("S.T::X", logic("A.Y", "DELETE FROM S.A")));
    }

    @Test
    public void matchesUnaliasedExpressionsAndRejectsHelperOutputs() {
        assertTrue(decider.logicSuppliesTargetValue("S.T::AGT_STATUS", logic(
                "XYZT", "SELECT XYZT, YWBQ FROM S.A")));
        assertTrue(decider.logicSuppliesTargetValue("S.T::AGT_STATUS", logic(
                "XYZT", "SELECT YWBQ, XYZT FROM S.A")));
        assertFalse(decider.logicSuppliesTargetValue("S.T::AGT_STATUS", logic(
                "XYZT", "SELECT XYZT AS HELPER, YWBQ FROM S.A")));
        assertFalse(decider.logicSuppliesTargetValue("S.T::AGT_STATUS", logic(
                "XYZT", "SELECT COALESCE(XYZT, '01'), YWBQ FROM S.A")));
        assertTrue(decider.logicSuppliesTargetValue("S.T::AGT_STATUS", logic(
                "XYZT", "SELECT XYZT, YWBQ AS BUSINESS_LABEL FROM S.A")));
        assertTrue(decider.logicSuppliesTargetValue("S.T::COUNT", logic(
                "COUNT(*)", "SELECT COUNT(*) FROM S.A")));
    }

    @Test
    public void supportsInsertCreateUnionAndUpdateStatements() {
        assertTrue(decider.logicSuppliesTargetValue("S.T::X", logic(
                "A.Y", "INSERT INTO S.T SELECT A.Y AS X FROM S.A A")));
        assertFalse(decider.logicSuppliesTargetValue("S.T::X", logic(
                "A.Y", "CREATE TABLE S.T AS SELECT A.Y AS HELPER FROM S.A A")));
        assertTrue(decider.logicSuppliesTargetValue("S.T::X", logic(
                "A.Y", "SELECT A.Y AS X FROM S.A A UNION ALL SELECT B.Y AS X FROM S.B B")));
        assertTrue(decider.logicSuppliesTargetValue("S.T::X", logic(
                "A.Y", "UPDATE S.T SET X = A.Y")));
        assertFalse(decider.logicSuppliesTargetValue("S.T::X", logic(
                "A.Y", "UPDATE S.T SET HELPER = A.Y")));
    }

    @Test
    public void adaptsGraphRelationsAndSavedEvidence() {
        Map<String, Object> relation = new LinkedHashMap<>();
        relation.put("target", "S.T::X");
        relation.put("logic", logic("A.Y", "SELECT A.Y AS X FROM S.A A"));
        assertTrue(decider.suppliesTargetValue(relation));
        relation.put("original_target", "S.T::OTHER");
        assertFalse(decider.suppliesTargetValue(relation));
        assertTrue(decider.suppliesTargetValue(Map.of("target", "S.T::X", "logic", "missing")));

        assertFalse(decider.evidenceSuppliesTargetValue(Map.of(
                "target", "S.T::X", "expression", "A.Y",
                "sql", "SELECT A.Y AS HELPER FROM S.A A")));
        assertTrue(decider.evidenceSuppliesTargetValue(Map.of(
                "original_target", "S.T::X", "target", "S.T::OTHER", "expression", "A.Y",
                "sql", "SELECT A.Y AS X FROM S.A A")));
    }

    @Test
    public void retainsUncertainRelationsAndExplainsFallbackInsteadOfClaimingAstSuccess() {
        Map<String, String> examples = Map.of(
                "parse_failed", "SELECT ( FROM",
                "multiple_statements", "UPDATE S.T SET Y=1; UPDATE S.T SET Z=2",
                "positional_insert", "INSERT INTO S.T(X) SELECT A.Y AS HELPER FROM S.A A",
                "incomplete_sql_fragment", "A.Y AS HELPER",
                "unresolved_statement", "SELECT A.* FROM S.A A");
        examples.forEach((reason, sql) -> {
            var decision = decider.assessLogic("S.T::X", logic("A.Y", sql));
            assertTrue(sql, decision.suppliesTargetValue());
            assertTrue(sql, decision.requiresReview());
            assertEquals(sql, "fallback", decision.status());
            assertEquals(sql, reason, decision.reason());
        });
        assertEquals("missing_target", decider.assessLogic(null, logic("A.Y",
                "SELECT A.Y AS HELPER FROM S.A A")).reason());
        assertTrue(decider.assessLogic("S.T::X", logic("A.Y, A.Z",
                "SELECT A.Y FROM S.A A")).requiresReview());
        assertTrue(decider.assessLogic("S.T::X", logic("", "SELECT A.Y FROM S.A A")).requiresReview());
        assertTrue(decider.assessLogic("S.T::X", logic("1", "UPDATE S.T SET (X,Y) = (1,2)")).requiresReview());
        assertTrue(decider.assessLogic("S.T::X", logic("A.Y",
                "CREATE TABLE S.T (X int) AS SELECT A.Y AS HELPER FROM S.A A")).requiresReview());
        assertEquals("json_explicit", decider.assessLogic("S.T::X", Map.of()).status());
    }

    @Test
    public void handlesSafeProjectionFragmentsAndV6ConstantMarkers() {
        var fragment = decider.assessLogic("S.T::X", logic("CONST:'CHN'", "'CHN' AS X"));
        assertTrue(fragment.suppliesTargetValue());
        assertFalse(fragment.requiresReview());
        assertEquals("fragment_matched", fragment.status());
        assertEquals("postgresql", fragment.dialect());
        assertTrue(decider.logicSuppliesTargetValue("S.T::X", logic("CONST:'CHN'", "SELECT 'CHN'")));
        assertTrue(decider.assessLogic("S.T::X", logic("A.X", "not valid sql (")).requiresReview());
        assertEquals("ast_excluded", decider.assessLogic("S.T::X",
                logic("A.Y", "SELECT A.Y AS HELPER FROM S.A A")).status());
    }

    @Test
    public void retainsAndReportsV6SingleCharacterOutputAliasCorrection() {
        var decision = decider.assessLogic("EAST.TARGET::GSYWDL", logic(
                "DECODE(SUBSTR(T.KJKMBH,1,1),'1','资产','其他')",
                "SELECT DECODE(SUBSTR(T.KJKMBH,1,1),'1','资产','其他') AS GWYWDL FROM EAST.SOURCE T"));

        assertTrue(decision.suppliesTargetValue());
        assertFalse(decision.requiresReview());
        assertEquals("ast_matched", decision.status());
        assertEquals("single_character_output_alias_typo", decision.correction().get("type"));
        assertEquals("GWYWDL", decision.correction().get("projected_field"));
        assertFalse(decider.logicSuppliesTargetValue("YBT.REPORT::DBHTLX", logic(
                "P.J030002", "SELECT P.J030002 AS DBXYID FROM YBT.HELPER P")));
        assertFalse(decider.logicSuppliesTargetValue("EAST.TARGET::GSYWDL", logic(
                "T.KJKMBH", "SELECT T.KJKMBH AS GWYWDL, T.OTHER FROM EAST.SOURCE T")));
    }

    @Test
    public void keepsLiteralCaseAndWhitespaceSignificantWhenMatchingExpressions() {
        assertFalse(decider.logicSuppliesTargetValue("S.T::GSYWDL", logic(
                "'a'", "SELECT 'A' AS GWYWDL FROM S.A")));
        assertFalse(decider.logicSuppliesTargetValue("S.T::GSYWDL", logic(
                "'a b'", "SELECT 'ab' AS GWYWDL FROM S.A")));
        assertFalse(decider.logicSuppliesTargetValue("S.T::X", logic("'a'", "SELECT 'A' FROM S.A")));
        assertFalse(decider.logicSuppliesTargetValue("S.T::X", logic("'a b'", "SELECT 'ab' FROM S.A")));
        assertTrue(decider.logicSuppliesTargetValue("S.T::X", logic(
                "((coalesce(t.x, 'a b')))", "SELECT COALESCE(T.X,'a b') FROM S.A T")));
    }

    @Test
    public void followsParentAndSiblingIntermediateValuePaths() {
        Map<String, Object> target = relation("path_1-1", "column:YBT.ORG::D030001",
                "column:YBT.REPORT::NBJGH", logic("D030001", "SELECT D030001 AS JGID FROM YBT.ORG"));
        var parent = decider.assessDerivation(List.of(target), Map.of(
                "path_1", logic("SUBSTR(T4.JGID,12)",
                        "SELECT SUBSTR(T4.JGID,12) AS NBJGH FROM YBT.TMP T4"),
                "path_1-1", logic("D030001", "SELECT D030001 AS JGID FROM YBT.ORG"))).get(0);
        assertTrue(parent.suppliesTargetValue());
        assertEquals("parent_path_matched", parent.status());
        assertEquals("parent_path_outputs_target", parent.correction().get("type"));
        assertEquals("JGID", parent.correction().get("intermediate_field"));

        Map<String, Object> direct = relation("path_1-1", "column:EAST.BASE::VALUE",
                "column:EAST.TARGET::SFSNDK", logic("NVL(FD.IS_SNDK,'否')",
                        "SELECT NVL(FD.IS_SNDK,'否') AS SFSNDK FROM EAST.BASE T"));
        Map<String, Object> intermediate = relation("path_1-2", "column:GSIS.SOURCE::FLAG",
                "column:EAST.TARGET::SFSNDK", logic("T.FLAG",
                        "SELECT T.FLAG AS IS_SNDK FROM GSIS.SOURCE T"));
        var sibling = decider.assessDerivation(List.of(direct, intermediate), Map.of(
                "path_1-1", logic("NVL(FD.IS_SNDK,'否')",
                        "SELECT NVL(FD.IS_SNDK,'否') AS SFSNDK FROM EAST.BASE T"),
                "path_1-2", logic("T.FLAG", "SELECT T.FLAG AS IS_SNDK FROM GSIS.SOURCE T")));
        assertTrue(sibling.get(1).suppliesTargetValue());
        assertEquals("sibling_path_matched", sibling.get(1).status());
        assertEquals("sibling_output_feeds_target", sibling.get(1).correction().get("type"));
        assertEquals("IS_SNDK", sibling.get(1).correction().get("intermediate_field"));
    }

    @Test
    public void tracesSubqueryAndMultipleIntermediateOutputsButIgnoresFilters() {
        Map<String, Object> direct = relation("path_1-1", "column:EAST.BASE::VALUE",
                "column:EAST.TARGET::YEAR_ACCUM", logic("'0'", "SELECT '0' AS YEAR_ACCUM FROM EAST.BASE"));
        Map<String, Object> valueSource = relation("path_1-2", "column:PIS.SOURCE::LSTBAL",
                "column:EAST.TARGET::YEAR_ACCUM", logic("SUM(SOURCE_BALANCE)",
                        "SELECT SUM(T.NORMAL_BALANCE) AS MONTH_ACCUM FROM "
                                + "(SELECT CASE WHEN FLAG=1 THEN LSTBAL ELSE BALANCE END AS NORMAL_BALANCE, "
                                + "UNUSED AS OTHER FROM PIS.SOURCE WHERE FILTER_ONLY=1) T"));
        Map<String, Object> filterOnly = relation("path_1-2", "column:PIS.SOURCE::FILTER_ONLY",
                "column:EAST.TARGET::YEAR_ACCUM", logic("SUM(SOURCE_BALANCE)",
                        "SELECT SUM(T.NORMAL_BALANCE) AS MONTH_ACCUM FROM "
                                + "(SELECT CASE WHEN FLAG=1 THEN LSTBAL ELSE BALANCE END AS NORMAL_BALANCE, "
                                + "UNUSED AS OTHER FROM PIS.SOURCE WHERE FILTER_ONLY=1) T"));
        Map<String, Object> consumer = logic("COALESCE(T.YEAR_ACCUM,0)",
                "SELECT COALESCE(T.YEAR_ACCUM,0) AS YEAR_ACCUM FROM "
                        + "(SELECT SUM(MONTH_ACCUM) AS YEAR_ACCUM FROM EDW.MONTHLY WHERE FILTER_ONLY=1) T");
        var decisions = decider.assessDerivation(List.of(direct, valueSource, filterOnly), Map.of(
                "path_1-1", consumer,
                "path_1-2", logic("SUM(SOURCE_BALANCE)",
                        "SELECT SUM(T.NORMAL_BALANCE) AS MONTH_ACCUM FROM "
                                + "(SELECT CASE WHEN FLAG=1 THEN LSTBAL ELSE BALANCE END AS NORMAL_BALANCE, "
                                + "UNUSED AS OTHER FROM PIS.SOURCE WHERE FILTER_ONLY=1) T")));
        assertTrue(decisions.get(1).suppliesTargetValue());
        assertEquals(List.of("MONTH_ACCUM"), decisions.get(1).correction().get("intermediate_fields"));
        assertFalse(decisions.get(2).suppliesTargetValue());
    }

    @Test
    public void followsSiblingValuesConsumedByTargetUpdateButIgnoresOtherAssignmentsAndFilters() {
        Map<String, Object> update = logic("COALESCE(CANDIDATE_STATUS,0)",
                "UPDATE BANK.TARGET SET TARGET_VALUE=COALESCE(\"CANDIDATE_STATUS\",0), "
                        + "AUX_VALUE=OTHER_STATUS WHERE FILTER_FLAG=1");
        Map<String, Object> value = logic("S.RAW_VALUE",
                "SELECT S.\"RAW_VALUE\" AS \"CANDIDATE_STATUS\" FROM BANK.SOURCE S");
        Map<String, Object> other = logic("S.OTHER_VALUE",
                "SELECT S.OTHER_VALUE AS OTHER_STATUS FROM BANK.SOURCE S");
        Map<String, Object> filter = logic("S.FILTER_VALUE",
                "SELECT S.FILTER_VALUE AS FILTER_FLAG FROM BANK.SOURCE S");
        var decisions = decider.assessDerivation(List.of(
                relation("path_1-1", "column:BANK.TMP::CANDIDATE_STATUS",
                        "column:BANK.TARGET::TARGET_VALUE", update),
                relation("path_1-2", "column:BANK.SOURCE::RAW_VALUE",
                        "column:BANK.TARGET::TARGET_VALUE", value),
                relation("path_1-3", "column:BANK.SOURCE::OTHER_VALUE",
                        "column:BANK.TARGET::TARGET_VALUE", other),
                relation("path_1-4", "column:BANK.SOURCE::FILTER_VALUE",
                        "column:BANK.TARGET::TARGET_VALUE", filter)), Map.of(
                "path_1-1", update, "path_1-2", value, "path_1-3", other, "path_1-4", filter));

        assertTrue(decisions.get(1).suppliesTargetValue());
        assertEquals("sibling_path_matched", decisions.get(1).status());
        assertEquals("CANDIDATE_STATUS", decisions.get(1).correction().get("intermediate_field"));
        assertFalse(decisions.get(2).suppliesTargetValue());
        assertFalse(decisions.get(3).suppliesTargetValue());
    }

    @Test
    public void exposesMultipleSiblingStepsWithoutInventingIntermediatePhysicalFields() {
        String target = "column:BANK.TARGET::KPZT";
        String source = "column:BANK.SOURCE::RAW_DATE";
        LineageGraph graph = graph("multiple-sibling-steps", "BANK.TARGET::KPZT", List.of(
                LineageSource.column("BANK.CARD::OPEN_DATE", "path_1-1"),
                LineageSource.column("BANK.SOURCE::RAW_DATE", "path_1-3"),
                LineageSource.column("BANK.SOURCE::FILTER_ONLY", "path_1-3"),
                LineageSource.column("BANK.UNRELATED::RAW_DATE", "path_2-1")), Map.of(
                "path_1-1", logic("CASE WHEN OPEN_DATE IS NULL THEN '0' ELSE '1' END",
                        "SELECT CASE WHEN OPEN_DATE IS NULL THEN '0' ELSE '1' END AS KPZT FROM BANK.CARD"),
                "path_1-2", logic("SUBSTR(KKRQ,1,8)",
                        "SELECT SUBSTR(KKRQ,1,8) AS OPEN_DATE FROM BANK.TMP"),
                "path_1-3", logic("RAW_DATE",
                        "SELECT RAW_DATE AS KKRQ, FILTER_ONLY AS UNUSED FROM BANK.SOURCE WHERE FILTER_ONLY=1"),
                "path_2-1", logic("RAW_DATE", "SELECT RAW_DATE AS KKRQ FROM BANK.UNRELATED")));

        assertTrue(graph.valueEdge(source, target));
        Map<?, ?> correction = (Map<?, ?>) firstEvidence(graph, source, target).get("value_lineage_correction");
        assertEquals("sibling_output_feeds_target", correction.get("type"));
        assertEquals("path_1-1", correction.get("target_path"));
        assertEquals("KKRQ", correction.get("intermediate_field"));
        assertEquals(List.of("KKRQ", "OPEN_DATE"), correction.get("intermediate_fields"));
        assertEquals(List.of("path_1-2"), correction.get("via_paths"));
        assertFalse(graph.valueEdge("column:BANK.SOURCE::FILTER_ONLY", target));
        assertFalse(graph.valueEdge("column:BANK.UNRELATED::RAW_DATE", target));
        assertThrows(IllegalArgumentException.class, () -> graph.resolve("column:BANK.TMP::KKRQ"));
    }

    @Test
    public void retainsExplicitSourcesWithReviewWhenEverySqlPathExcludesTarget() {
        List<Map<String, Object>> relations = List.of(
                relation("path_1-1", "column:S.A::Y", "column:S.T::X",
                        logic("A.Y", "SELECT A.Y AS HELPER FROM S.A A")),
                relation("path_1-2", "column:S.B::Z", "column:S.T::X",
                        logic("B.Z", "SELECT B.Z AS OTHER FROM S.B B")));
        var decisions = decider.assessDerivation(relations, Map.of());

        assertTrue(decisions.stream().allMatch(decision -> decision.suppliesTargetValue()));
        assertTrue(decisions.stream().allMatch(ValueLineageDecision::requiresReview));
        assertTrue(decisions.stream().allMatch(decision -> "declared_source_fallback"
                .equals(decision.correction().get("type"))));
    }

    @Test
    public void exposesV6ValueCorrectionsInGraphEvidenceAndFiltersHelperEdges() {
        String parentSource = "column:BANK.SOURCE::D030001";
        String parentTarget = "column:BANK.TARGET::NBJGH";
        LineageGraph parentGraph = graph("parent-correction", "BANK.TARGET::NBJGH",
                List.of(LineageSource.column("BANK.SOURCE::D030001", "path_1-1")), Map.of(
                        "path_1", logic("SUBSTR(T.JGID,12)",
                                "SELECT SUBSTR(T.JGID,12) AS NBJGH FROM BANK.PARENT T"),
                        "path_1-1", logic("S.D030001",
                                "SELECT S.D030001 AS JGID FROM BANK.SOURCE S")));
        assertTrue(parentGraph.valueEdge(parentSource, parentTarget));
        Map<?, ?> parentEvidence = firstEvidence(parentGraph, parentSource, parentTarget);
        Map<?, ?> parentCorrection = (Map<?, ?>) parentEvidence.get("value_lineage_correction");
        assertEquals("parent_path_outputs_target", parentCorrection.get("type"));
        assertEquals("JGID", parentCorrection.get("intermediate_field"));

        String siblingSource = "column:BANK.AUX::FLAG";
        String siblingTarget = "column:BANK.TARGET::SFSNDK";
        LineageGraph siblingGraph = graph("sibling-correction", "BANK.TARGET::SFSNDK", List.of(
                LineageSource.column("BANK.DIRECT::IS_SNDK", "path_1-1"),
                LineageSource.column("BANK.AUX::FLAG", "path_1-2")), Map.of(
                "path_1-1", logic("NVL(D.IS_SNDK,'N')",
                        "SELECT NVL(D.IS_SNDK,'N') AS SFSNDK FROM BANK.DIRECT D"),
                "path_1-2", logic("A.FLAG",
                        "SELECT A.FLAG AS IS_SNDK FROM BANK.AUX A")));
        assertTrue(siblingGraph.valueEdge(siblingSource, siblingTarget));
        Map<?, ?> siblingCorrection = (Map<?, ?>) firstEvidence(siblingGraph, siblingSource, siblingTarget)
                .get("value_lineage_correction");
        assertEquals("sibling_output_feeds_target", siblingCorrection.get("type"));
        assertEquals(List.of("IS_SNDK"), siblingCorrection.get("intermediate_fields"));

        String helperSource = "column:BANK.AUX::J030002";
        String helperTarget = "column:BANK.TARGET::DBHTLX";
        LineageGraph helperGraph = graph("helper-filter", "BANK.TARGET::DBHTLX", List.of(
                LineageSource.column("BANK.DIRECT::DBHTLX", "path_1-1"),
                LineageSource.column("BANK.AUX::J030002", "path_1-2")), Map.of(
                "path_1-1", logic("D.DBHTLX",
                        "SELECT D.DBHTLX AS DBHTLX FROM BANK.DIRECT D"),
                "path_1-2", logic("A.J030002",
                        "SELECT A.J030002 AS DBXYID FROM BANK.AUX A")));
        assertTrue(helperGraph.valueEdge("column:BANK.DIRECT::DBHTLX", helperTarget));
        assertFalse(helperGraph.valueEdge(helperSource, helperTarget));
    }

    @Test
    public void graphRetainsAllUnrecognizedExplicitSourcesWithOneReviewIssuePerLogicPath() {
        String target = "column:BANK.TARGET::X";
        String firstSource = "column:BANK.A::Y";
        String secondSource = "column:BANK.B::Z";
        LineageGraph graph = graph("declared-source-fallback", "BANK.TARGET::X", List.of(
                LineageSource.column("BANK.A::Y", "path_1-1"),
                LineageSource.column("BANK.B::Z", "path_1-2")), Map.of(
                "path_1", logic("A.Y", "SELECT A.Y AS HELPER FROM BANK.A A")));

        assertTrue(graph.valueEdge(firstSource, target));
        assertTrue(graph.valueEdge(secondSource, target));
        assertEquals(1L, graph.issues().stream()
                .filter(issue -> issue.code().equals("SQL_ANALYSIS_FALLBACK")).count());
        Map<?, ?> evidence = firstEvidence(graph, firstSource, target);
        assertTrue(Boolean.TRUE.equals(((Map<?, ?>) evidence.get("sql_analysis")).get("requires_review")));
        assertEquals("declared_source_fallback",
                ((Map<?, ?>) evidence.get("value_lineage_correction")).get("type"));
    }

    private LineageGraph graph(String id, String target, List<LineageSource> sources,
                               Map<String, Map<String, Object>> transformLogic) {
        Derivation derivation = new Derivation(target, sources, transformLogic, Map.of());
        LineageDocument document = new LineageDocument("1.0", id, id + ".sql",
                List.of(derivation), Map.of());
        return new LineageGraph(List.of(document), List.of(), decider);
    }

    private Map<?, ?> firstEvidence(LineageGraph graph, String source, String target) {
        List<?> items = (List<?>) graph.evidence(target, source, target, 0, 10, "").get("items");
        return (Map<?, ?>) items.get(0);
    }

    private Map<String, Object> relation(String path, String source, String target, Map<String, Object> logic) {
        return Map.of("path", path, "source", source, "target", target,
                "original_source", source, "original_target", target, "logic", logic);
    }

    private Map<String, Object> logic(String expression, String sql) {
        return Map.of("expression", expression, "sql_fragment", sql);
    }
}
