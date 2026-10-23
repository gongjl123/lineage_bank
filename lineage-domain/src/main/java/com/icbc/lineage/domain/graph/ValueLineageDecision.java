package com.icbc.lineage.domain.graph;

import java.util.Map;

/** Value-lineage classification, including explicit uncertainty instead of silent success. */
public record ValueLineageDecision(boolean suppliesTargetValue, String status, String reason, String dialect) {

    public boolean requiresReview() {
        return status.equals("fallback");
    }

    public Map<String, Object> toMap() {
        return Map.of("supplies_target_value", suppliesTargetValue, "status", status,
                "reason", reason, "dialect", dialect, "requires_review", requiresReview());
    }
}
