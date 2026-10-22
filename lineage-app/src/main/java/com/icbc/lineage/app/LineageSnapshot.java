package com.icbc.lineage.app;

import com.icbc.lineage.api.storage.StoredDocument;
import com.icbc.lineage.domain.graph.LineageGraph;
import com.icbc.lineage.domain.graph.MappedLineageGraph;

import java.util.Map;

public record LineageSnapshot(
        LineageGraph raw,
        MappedLineageGraph mapped,
        Map<String, StoredDocument> documents) {

    public LineageSnapshot {
        documents = Map.copyOf(documents);
    }
}
