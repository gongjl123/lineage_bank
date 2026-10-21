package com.icbc.lineage.domain.graph;

import java.util.Map;

@FunctionalInterface
public interface ValueLineageDecider {

    boolean suppliesTargetValue(Map<String, Object> relation);
}
