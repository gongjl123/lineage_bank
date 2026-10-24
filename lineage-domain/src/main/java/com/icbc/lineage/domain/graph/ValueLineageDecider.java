package com.icbc.lineage.domain.graph;

import java.util.Map;
import java.util.List;

@FunctionalInterface
public interface ValueLineageDecider {

    boolean suppliesTargetValue(Map<String, Object> relation);

    default ValueLineageDecision assess(Map<String, Object> relation) {
        return new ValueLineageDecision(suppliesTargetValue(relation), "json_explicit", "explicit_sources", "");
    }

    default List<ValueLineageDecision> assessDerivation(List<Map<String, Object>> relations,
                                                        Map<String, Map<String, Object>> transformLogic) {
        return relations.stream().map(this::assess).toList();
    }
}
