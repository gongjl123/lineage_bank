package com.icbc.lineage.domain.graph;

import java.util.Map;
import java.util.LinkedHashMap;

/** Value-lineage classification, including explicit uncertainty instead of silent success. */
public record ValueLineageDecision(boolean suppliesTargetValue, String status, String reason, String dialect,
                                   Map<String, Object> correction) {

    public ValueLineageDecision(boolean suppliesTargetValue, String status, String reason, String dialect) {
        this(suppliesTargetValue, status, reason, dialect, Map.of());
    }

    public ValueLineageDecision {
        correction = correction == null ? Map.of()
                : Map.copyOf(new LinkedHashMap<>(correction));
    }

    public boolean requiresReview() {
        return status.equals("fallback");
    }

    public Map<String, Object> toMap() {
        return Map.of("supplies_target_value", suppliesTargetValue, "status", status,
                "reason", reason, "dialect", dialect, "requires_review", requiresReview());
    }
}
