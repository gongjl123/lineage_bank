package com.icbc.lineage.domain.model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Collections;

public record Derivation(
        String target,
        List<LineageSource> sources,
        Map<String, Map<String, Object>> transformLogic,
        Map<String, Object> metadata) {

    public Derivation {
        sources = List.copyOf(sources);
        Map<String, Map<String, Object>> copiedLogic = new LinkedHashMap<>();
        transformLogic.forEach((key, value) -> copiedLogic.put(key,
                Collections.unmodifiableMap(new LinkedHashMap<>(value))));
        transformLogic = Collections.unmodifiableMap(copiedLogic);
        metadata = Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
    }
}
