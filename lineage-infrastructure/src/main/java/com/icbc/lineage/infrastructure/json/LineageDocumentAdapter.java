package com.icbc.lineage.infrastructure.json;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.icbc.lineage.domain.exception.DataValidationException;
import com.icbc.lineage.domain.model.BusinessLine;
import com.icbc.lineage.domain.model.Derivation;
import com.icbc.lineage.domain.model.LineageDocument;
import com.icbc.lineage.domain.model.LineageIssue;
import com.icbc.lineage.domain.model.LineageSource;
import com.icbc.lineage.domain.model.NormalizedDocument;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class LineageDocumentAdapter {

    private static final Pattern LEGACY_SOURCE = Pattern.compile("^<([^>]+)>(.*)$", Pattern.DOTALL);
    private static final TypeReference<Map<String, Object>> OBJECT_MAP = new TypeReference<>() { };
    private static final TypeReference<Map<String, Map<String, Object>>> LOGIC_MAP = new TypeReference<>() { };

    private final LineageJsonParser parser;
    private final LineageSchemaValidator schemaValidator;
    private final LineageSemanticValidator semanticValidator;

    public LineageDocumentAdapter(LineageJsonParser parser,
                                  LineageSchemaValidator schemaValidator,
                                  LineageSemanticValidator semanticValidator) {
        this.parser = parser;
        this.schemaValidator = schemaValidator;
        this.semanticValidator = semanticValidator;
    }

    public NormalizedDocument parseAndNormalize(String text, String filename, BusinessLine requestedLine) {
        JsonNode original = parser.parse(text, filename);
        boolean standard = original.isObject() && original.has("schema_version");
        schemaValidator.validate(original, standard, filename);
        Adapted adapted = standard ? standard(original) : legacy(original, filename);
        List<LineageIssue> issues = new ArrayList<>(adapted.warnings());
        issues.addAll(semanticValidator.validate(adapted.document()));
        return new NormalizedDocument(filename, requestedLine, original, adapted.document(), issues);
    }

    private Adapted standard(JsonNode root) {
        ObjectMapper mapper = parser.objectMapper();
        List<Derivation> derivations = new ArrayList<>();
        for (JsonNode node : root.path("derivations")) {
            derivations.add(readDerivation(node, false));
        }
        Map<String, Object> metadata = root.has("metadata")
                ? mapper.convertValue(root.get("metadata"), OBJECT_MAP) : Map.of();
        LineageDocument document = new LineageDocument(root.path("schema_version").asText(),
                root.path("dataset_id").asText(), root.path("script_name").asText(), derivations, metadata);
        return new Adapted(document, List.of());
    }

    private Adapted legacy(JsonNode root, String filename) {
        List<Derivation> derivations = new ArrayList<>();
        Set<String> actualSources = new HashSet<>();
        Set<String> actualTargets = new HashSet<>();
        for (JsonNode node : root.path("derivations")) {
            Derivation derivation = readDerivation(node, true);
            derivations.add(derivation);
            actualTargets.add(derivation.target());
            derivation.sources().stream().filter(source -> source.ref() != null)
                    .forEach(source -> actualSources.add(source.ref()));
        }
        String datasetId = "legacy:" + filename.replace('\\', '/');
        Map<String, Object> metadata = Map.of("format", "legacy", "filename", filename);
        LineageDocument document = new LineageDocument("1.0", datasetId,
                root.path("script_name").asText(), derivations, metadata);
        List<LineageIssue> warnings = new ArrayList<>();
        summaryWarning(root, "sources", actualSources, datasetId, warnings);
        summaryWarning(root, "targets", actualTargets, datasetId, warnings);
        return new Adapted(document, warnings);
    }

    private Derivation readDerivation(JsonNode node, boolean legacy) {
        ObjectMapper mapper = parser.objectMapper();
        List<LineageSource> sources = new ArrayList<>();
        for (JsonNode source : node.path("sources")) {
            if (legacy) {
                Matcher matcher = LEGACY_SOURCE.matcher(source.asText());
                if (!matcher.matches()) {
                    throw new IllegalStateException("Schema accepted an invalid legacy source");
                }
                String path = matcher.group(1);
                String ref = matcher.group(2);
                sources.add(ref.startsWith("CONST:")
                        ? LineageSource.constant(ref.substring(6), path)
                        : LineageSource.column(ref, path));
            } else if ("column".equals(source.path("kind").asText())) {
                sources.add(LineageSource.column(source.path("ref").asText(), source.path("path").asText()));
            } else {
                sources.add(LineageSource.constant(source.path("value").asText(), source.path("path").asText()));
            }
        }
        Map<String, Map<String, Object>> logic = mapper.convertValue(node.path("transform_logic"), LOGIC_MAP);
        Map<String, Object> metadata = node.has("metadata")
                ? mapper.convertValue(node.get("metadata"), OBJECT_MAP) : Map.of();
        return new Derivation(node.path("target").asText(), sources, logic, metadata);
    }

    private void summaryWarning(JsonNode root, String key, Set<String> actual,
                                String datasetId, List<LineageIssue> warnings) {
        if (!root.has(key)) {
            return;
        }
        Set<String> declared = new HashSet<>();
        root.path(key).forEach(value -> declared.add(value.asText()));
        if (!declared.equals(actual)) {
            warnings.add(LineageIssue.of("SUMMARY_MISMATCH", "顶层汇总与派生记录不一致，以派生记录为准。",
                    datasetId, key, LineageIssue.Severity.WARNING));
        }
    }

    private record Adapted(LineageDocument document, List<LineageIssue> warnings) {
    }
}
