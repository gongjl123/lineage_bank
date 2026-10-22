package com.icbc.lineage.api;

import com.icbc.lineage.domain.model.BusinessLine;

public record ImportFile(BusinessLine line, String filename, String content) {
}
