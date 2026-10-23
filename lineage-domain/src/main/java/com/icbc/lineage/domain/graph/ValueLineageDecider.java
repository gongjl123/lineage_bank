package com.icbc.lineage.domain.graph;

import java.util.Map;

@FunctionalInterface
public interface ValueLineageDecider {

    boolean suppliesTargetValue(Map<String, Object> relation);

    default ValueLineageDecision assess(Map<String, Object> relation) {
        return new ValueLineageDecision(suppliesTargetValue(relation), "json_explicit", "explicit_sources", "");
    }
}
