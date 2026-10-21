package com.icbc.lineage.infrastructure.json;

import com.fasterxml.jackson.databind.JsonNode;
import com.icbc.lineage.domain.exception.DataValidationException;
import com.icbc.lineage.domain.model.LineageIssue;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.Comparator;
import java.util.List;

@Component
public class LineageSchemaValidator {

    private final JsonSchema lineageSchema;
    private final JsonSchema legacySchema;

    public LineageSchemaValidator() {
        JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
        lineageSchema = load(factory, "/schemas/lineage.schema.json");
        legacySchema = load(factory, "/schemas/legacy.schema.json");
    }

    public void validate(JsonNode data, boolean standard, String filename) {
        List<LineageIssue> issues = (standard ? lineageSchema : legacySchema).validate(data).stream()
                .sorted(Comparator.comparing(ValidationMessage::getPath)
                        .thenComparing(ValidationMessage::getMessage))
                .limit(50)
                .map(message -> LineageIssue.of("SCHEMA_ERROR", abbreviate(message.getMessage()), filename,
                        message.getPath(), LineageIssue.Severity.ERROR))
                .toList();
        if (!issues.isEmpty()) {
            throw new DataValidationException(issues);
        }
    }

    private JsonSchema load(JsonSchemaFactory factory, String path) {
        try (InputStream stream = LineageSchemaValidator.class.getResourceAsStream(path)) {
            if (stream == null) {
                throw new IllegalStateException("Missing schema resource: " + path);
            }
            return factory.getSchema(stream);
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("Unable to load schema: " + path, exception);
        }
    }

    private String abbreviate(String message) {
        return message.length() <= 600 ? message : message.substring(0, 600);
    }
}
