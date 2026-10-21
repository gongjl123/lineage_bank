package com.icbc.lineage.domain.model;

import com.icbc.lineage.domain.exception.DataValidationException;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class testLineageModels {

    @Test
    public void testBusinessLineAndFactories() {
        Assert.assertEquals(BusinessLine.EAST, BusinessLine.from(null));
        Assert.assertEquals(BusinessLine.EAST, BusinessLine.from(" "));
        Assert.assertEquals(BusinessLine.YBT, BusinessLine.from("ybt"));
        Assert.assertEquals("standard", BusinessLine.STANDARD.code());
        Assert.assertEquals(SourceKind.COLUMN, LineageSource.column("S.T::C", "p").kind());
        Assert.assertEquals(SourceKind.CONSTANT, LineageSource.constant("1", "p").kind());
    }

    @Test
    public void testModelsDefensivelyCopyAndExposeIssues() {
        List<LineageSource> sources = new ArrayList<>(List.of(LineageSource.column("S.T::C", "p")));
        Map<String, Object> logicRow = new LinkedHashMap<>(Map.of("expression", "C"));
        Map<String, Map<String, Object>> logic = new LinkedHashMap<>(Map.of("p", logicRow));
        Derivation derivation = new Derivation("S.O::C", sources, logic, Map.of());
        sources.clear();
        logicRow.put("changed", true);
        Assert.assertEquals(1, derivation.sources().size());
        Assert.assertFalse(derivation.transformLogic().get("p").containsKey("changed"));

        LineageDocument document = new LineageDocument("1.0", "d", "a.sql", List.of(derivation),
                Map.of("business_line", "std"));
        Assert.assertEquals(BusinessLine.STD, document.businessLine(BusinessLine.EAST));
        Assert.assertEquals(BusinessLine.YBT,
                new LineageDocument("1.0", "d2", "b.sql", List.of(), Map.of()).businessLine(BusinessLine.YBT));

        LineageIssue issue = LineageIssue.of("X", "message", "d", "/", LineageIssue.Severity.WARNING)
                .with("target", "S.O::C");
        Assert.assertEquals("S.O::C", issue.data().get("target"));
        DataValidationException exception = new DataValidationException(List.of(issue));
        Assert.assertEquals("message", exception.getMessage());
        Assert.assertEquals(List.of(issue), exception.getIssues());
        Assert.assertEquals("Invalid lineage data", new DataValidationException(List.of()).getMessage());
    }
}
