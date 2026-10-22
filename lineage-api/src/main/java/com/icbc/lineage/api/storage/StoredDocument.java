package com.icbc.lineage.api.storage;

import com.fasterxml.jackson.databind.JsonNode;
import com.icbc.lineage.domain.model.LineageDocument;
import com.icbc.lineage.domain.model.LineageIssue;

import java.time.Instant;
import java.util.List;

public record StoredDocument(
        String id,
        String line,
        String filename,
        String hash,
        JsonNode original,
        LineageDocument normalized,
        List<LineageIssue> issues,
        Instant updatedAt) {

    public StoredDocument {
        issues = List.copyOf(issues);
    }
}
