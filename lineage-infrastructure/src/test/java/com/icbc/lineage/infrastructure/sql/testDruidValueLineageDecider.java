package com.icbc.lineage.infrastructure.sql;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
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

    private Map<String, Object> logic(String expression, String sql) {
        return Map.of("expression", expression, "sql_fragment", sql);
    }
}
