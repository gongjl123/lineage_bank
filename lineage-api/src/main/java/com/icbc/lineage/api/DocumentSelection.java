package com.icbc.lineage.api;

import com.fasterxml.jackson.annotation.JsonProperty;

public record DocumentSelection(@JsonProperty("dataset_id") String datasetId, String hash) {
}
