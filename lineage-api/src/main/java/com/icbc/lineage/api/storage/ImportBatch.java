package com.icbc.lineage.api.storage;

import java.time.Instant;

public record ImportBatch(
        String id,
        int changed,
        int unchanged,
        int inputCount,
        long elapsedMillis,
        Instant createdAt) {
}
