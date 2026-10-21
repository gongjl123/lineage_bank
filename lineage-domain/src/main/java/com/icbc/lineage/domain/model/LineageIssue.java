package com.icbc.lineage.domain.model;

import java.util.Map;
import java.util.Collections;
import java.util.LinkedHashMap;

public record LineageIssue(
        String code,
        String message,
        String document,
        String location,
        Severity severity,
        Map<String, Object> data) {

    public LineageIssue {
        data = Collections.unmodifiableMap(new LinkedHashMap<>(data));
    }

    public enum Severity {
        INFO, WARNING, ERROR
    }

    public static LineageIssue of(String code, String message, String document,
                                  String location, Severity severity) {
        return new LineageIssue(code, message, document, location, severity, Map.of());
    }

    public LineageIssue with(String key, Object value) {
        java.util.LinkedHashMap<String, Object> values = new java.util.LinkedHashMap<>(data);
        values.put(key, value);
        return new LineageIssue(code, message, document, location, severity, values);
    }
}
