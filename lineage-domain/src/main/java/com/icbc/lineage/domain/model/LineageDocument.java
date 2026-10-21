package com.icbc.lineage.domain.model;

import java.util.List;
import java.util.Map;
import java.util.Collections;
import java.util.LinkedHashMap;

public record LineageDocument(
        String schemaVersion,
        String datasetId,
        String scriptName,
        List<Derivation> derivations,
        Map<String, Object> metadata) {

    public LineageDocument {
        derivations = List.copyOf(derivations);
        metadata = Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
    }

    public BusinessLine businessLine(BusinessLine fallback) {
        Object value = metadata.get("business_line");
        return value == null ? fallback : BusinessLine.from(String.valueOf(value));
    }
}
