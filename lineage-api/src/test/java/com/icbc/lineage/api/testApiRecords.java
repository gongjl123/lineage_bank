package com.icbc.lineage.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.icbc.lineage.api.storage.ImportBatch;
import com.icbc.lineage.api.storage.StoredDocument;
import com.icbc.lineage.domain.model.BusinessLine;
import com.icbc.lineage.domain.model.LineageDocument;
import org.junit.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class testApiRecords {

    @Test
    public void transportRecordsKeepImmutableValues() throws Exception {
        ImportFile file = new ImportFile(BusinessLine.EAST, "a.json", "{}");
        DocumentSelection selection = new DocumentSelection("east:a", "hash");
        ImportBatch batch = new ImportBatch("batch", 1, 0, 1, 3, Instant.EPOCH);
        ArrayList<com.icbc.lineage.domain.model.LineageIssue> issues = new ArrayList<>();
        StoredDocument stored = new StoredDocument("east:a", "east", file.filename(), selection.hash(),
                new ObjectMapper().readTree("{}"),
                new LineageDocument("1.0", "east:a", "a.sql", List.of(), Map.of()), issues, Instant.EPOCH);
        issues.clear();
        assertEquals("east", file.line().code());
        assertEquals("{}", file.content());
        assertEquals("east:a", selection.datasetId());
        assertEquals(1, batch.changed());
        assertEquals("batch", batch.id());
        assertEquals(0, batch.unchanged());
        assertEquals(1, batch.inputCount());
        assertEquals(3, batch.elapsedMillis());
        assertEquals(Instant.EPOCH, batch.createdAt());
        assertNotNull(stored.original());
        assertEquals("east:a", stored.id());
        assertEquals("east", stored.line());
        assertEquals("a.json", stored.filename());
        assertEquals("hash", stored.hash());
        assertEquals("a.sql", stored.normalized().scriptName());
        assertEquals(Instant.EPOCH, stored.updatedAt());
        assertEquals(List.of(), stored.issues());
    }
}
