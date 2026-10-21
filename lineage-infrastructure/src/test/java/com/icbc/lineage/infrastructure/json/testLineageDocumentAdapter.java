package com.icbc.lineage.infrastructure.json;

import com.icbc.lineage.domain.exception.DataValidationException;
import com.icbc.lineage.domain.model.BusinessLine;
import com.icbc.lineage.domain.model.Derivation;
import com.icbc.lineage.domain.model.LineageDocument;
import com.icbc.lineage.domain.model.LineageIssue;
import com.icbc.lineage.domain.model.NormalizedDocument;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.util.List;
import java.util.Map;

public class testLineageDocumentAdapter {

    private LineageJsonParser parser;
    private LineageSchemaValidator schema;
    private LineageSemanticValidator semantics;
    private LineageDocumentAdapter adapter;

    @Before
    public void setUp() {
        parser = new LineageJsonParser();
        schema = new LineageSchemaValidator();
        semantics = new LineageSemanticValidator();
        adapter = new LineageDocumentAdapter(parser, schema, semantics);
    }

    @Test
    public void testStandardDocumentIsParsedAndSemanticIssuesAreRetained() {
        String json = """
                {
                  "schema_version":"1.0",
                  "dataset_id":"east-a",
                  "script_name":"a.sql",
                  "metadata":{"business_line":"east", "nullable":null},
                  "derivations":[
                    {
                      "target":"S.OUT::C",
                      "sources":[
                        {"kind":"column","ref":"S.OUT::C","path":"p-1"},
                        {"kind":"constant","value":"01","path":"p-2"}
                      ],
                      "transform_logic":{
                        "p":{"expression":"C"},
                        "unused":{"expression":"X"}
                      },
                      "metadata":{"x":null}
                    },
                    {
                      "target":"S.OUT::C",
                      "sources":[],
                      "transform_logic":{"q":{"expression":"1"}}
                    }
                  ]
                }
                """;
        NormalizedDocument result = adapter.parseAndNormalize(json, "a.json", BusinessLine.STD);
        Assert.assertEquals("east-a", result.document().datasetId());
        Assert.assertEquals(BusinessLine.EAST, result.document().businessLine(result.requestedLine()));
        Assert.assertEquals(2, result.document().derivations().size());
        Assert.assertNull(result.document().metadata().get("nullable"));
        Assert.assertNull(result.document().derivations().get(0).metadata().get("x"));
        Assert.assertTrue(result.issues().stream().anyMatch(issue -> "SELF_DEPENDENCY".equals(issue.code())));
        Assert.assertTrue(result.issues().stream().anyMatch(issue -> "UNUSED_LOGIC".equals(issue.code())));
        Assert.assertTrue(result.issues().stream().anyMatch(issue -> "EMPTY_SOURCES".equals(issue.code())));
        Assert.assertTrue(result.issues().stream().anyMatch(issue -> "MULTIPLE_DERIVATIONS".equals(issue.code())));
    }

    @Test
    public void testLegacyDocumentNormalizesColumnsConstantsAndSummaryWarnings() {
        String json = """
                {
                  "script_name":"legacy.sql",
                  "sources":["WRONG::SOURCE"],
                  "targets":["S.OUT::OTHER"],
                  "derivations":[{
                    "target":"S.OUT::C",
                    "sources":["<path-1>S.IN::A","<path-2>CONST:固定值"],
                    "transform_logic":{"path-1":{"expression":"A"},"path-2":{"expression":"'固定值'"}}
                  }]
                }
                """;
        NormalizedDocument result = adapter.parseAndNormalize(json, "dir\\legacy.json", BusinessLine.YBT);
        Assert.assertEquals("legacy:dir/legacy.json", result.document().datasetId());
        Assert.assertEquals("legacy", result.document().metadata().get("format"));
        Assert.assertEquals("S.IN::A", result.document().derivations().get(0).sources().get(0).ref());
        Assert.assertEquals("固定值", result.document().derivations().get(0).sources().get(1).value());
        Assert.assertEquals(2, result.issues().stream().filter(issue -> "SUMMARY_MISMATCH".equals(issue.code())).count());
    }

    @Test
    public void testInvalidJsonAndSchemaReturnStructuredErrors() {
        for (String json : List.of("{\"x\":1,\"x\":2}", "{\"x\":NaN}", "{")) {
            try {
                parser.parse(json, "bad.json");
                Assert.fail("Expected invalid JSON");
            } catch (DataValidationException exception) {
                Assert.assertEquals("INVALID_JSON", exception.getIssues().get(0).code());
                Assert.assertEquals(LineageIssue.Severity.ERROR, exception.getIssues().get(0).severity());
            }
        }

        try {
            adapter.parseAndNormalize("{\"schema_version\":\"1.0\"}", "bad.json", BusinessLine.EAST);
            Assert.fail("Expected schema failure");
        } catch (DataValidationException exception) {
            Assert.assertTrue(exception.getIssues().stream().allMatch(issue -> "SCHEMA_ERROR".equals(issue.code())));
            Assert.assertTrue(exception.getIssues().size() <= 50);
        }
    }

    @Test
    public void testSemanticHelpersUseNearestParentAndInputFiltering() {
        Map<String, Map<String, Object>> logic = Map.of("p", Map.of(), "p-1", Map.of());
        Assert.assertEquals("p-1", semantics.resolveLogicPath(logic, "p-1-2"));
        Assert.assertNull(semantics.resolveLogicPath(logic, "missing"));
        Assert.assertNull(semantics.resolveLogicPath(null, "p"));
        Assert.assertNull(semantics.resolveLogicPath(logic, null));
        Assert.assertTrue(semantics.ignoredInputPath("x/.ipynb_checkpoints/a.json"));
        Assert.assertTrue(semantics.ignoredInputPath("x\\.IPYNB_CHECKPOINTS\\a.json"));
        Assert.assertFalse(semantics.ignoredInputPath("x/a.json"));
        Assert.assertFalse(semantics.ignoredInputPath(null));

        Derivation empty = new Derivation("S.T::C", List.of(), Map.of(), Map.of());
        Assert.assertFalse(semantics.needsSourceReview(empty));
        LineageIssue issue = semantics.emptySourceIssue("d", 0, "S.T::C", 2);
        Assert.assertEquals("EMPTY_SOURCES_USED", issue.code());
        Assert.assertEquals(LineageIssue.Severity.WARNING, issue.severity());

        LineageDocument document = new LineageDocument("1.0", "d", "a.sql", List.of(empty), Map.of());
        Assert.assertTrue(semantics.validate(document).isEmpty());
        Assert.assertNotNull(parser.objectMapper());
    }
}
