package com.icbc.lineage.domain.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

public record NormalizedDocument(
        String filename,
        BusinessLine requestedLine,
        JsonNode original,
        LineageDocument document,
        List<LineageIssue> issues) {

    public NormalizedDocument {
        issues = List.copyOf(issues);
    }
}
