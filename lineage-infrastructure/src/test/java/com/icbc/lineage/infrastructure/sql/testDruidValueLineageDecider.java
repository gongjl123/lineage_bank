package com.icbc.lineage.infrastructure.sql;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertFalse;
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

    private Map<String, Object> logic(String expression, String sql) {
        return Map.of("expression", expression, "sql_fragment", sql);
    }
}
