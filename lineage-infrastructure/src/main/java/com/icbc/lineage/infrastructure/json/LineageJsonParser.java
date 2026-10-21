package com.icbc.lineage.infrastructure.json;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.icbc.lineage.domain.exception.DataValidationException;
import com.icbc.lineage.domain.model.LineageIssue;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;

@Component
public class LineageJsonParser {

    private final ObjectMapper objectMapper;

    public LineageJsonParser() {
        JsonFactory factory = JsonFactory.builder()
                .enable(JsonFactory.Feature.INTERN_FIELD_NAMES)
                .build();
        factory.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        this.objectMapper = new ObjectMapper(factory);
    }

    public JsonNode parse(String text, String filename) {
        try {
            String value = text != null && text.startsWith("\ufeff") ? text.substring(1) : text;
            return objectMapper.readTree(value);
        } catch (IOException | RuntimeException exception) {
            LineageIssue issue = LineageIssue.of("INVALID_JSON", safeMessage(exception), filename,
                    "", LineageIssue.Severity.ERROR);
            throw new DataValidationException(List.of(issue));
        }
    }

    public ObjectMapper objectMapper() {
        return objectMapper;
    }

    private String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }
}
