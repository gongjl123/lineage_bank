package com.icbc.lineage.app;

import com.icbc.lineage.api.ImportFile;
import com.icbc.lineage.api.DocumentSelection;
import com.icbc.lineage.api.storage.LineageRepository;
import com.icbc.lineage.domain.catalog.NameDictionary;
import com.icbc.lineage.domain.catalog.TableNameMapper;
import com.icbc.lineage.domain.model.BusinessLine;
import com.icbc.lineage.infrastructure.json.LineageDocumentAdapter;
import com.icbc.lineage.infrastructure.json.LineageJsonParser;
import com.icbc.lineage.infrastructure.json.LineageSchemaValidator;
import com.icbc.lineage.infrastructure.json.LineageSemanticValidator;
import com.icbc.lineage.infrastructure.sql.DruidValueLineageDecider;
import com.icbc.lineage.infrastructure.storage.JdbcLineageRepository;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class testLineageStore {

    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void importsIncrementallyUpdatesAndRestoresHistory() throws Exception {
        Path database = temporary.newFile("store.sqlite3").toPath();
        LineageStore store = store(repository(database));

        Map<String, Object> first = store.importFiles(List.of(file("first", "S.B::X", "S.A::X")), false);
        assertEquals(1, first.get("changed"));
        assertEquals(1, store.snapshot().documents().size());
        Map<String, Object> second = store.importFiles(List.of(file("second", "S.C::X", "S.B::X")), false);
        assertEquals(1, second.get("changed"));
        assertEquals(2, store.snapshot().documents().size());
        assertEquals(List.of("table:S.A", "table:S.B", "table:S.C"),
                store.snapshot().raw().tablePath("table:S.A", "table:S.C", "downstream", 20).get("path"));

        Map<String, Object> unchanged = store.importFiles(List.of(file("first", "S.B::X", "S.A::X")), false);
        assertEquals(0, unchanged.get("changed"));
        assertEquals(1, unchanged.get("unchanged"));
        Object oldSnapshot = store.snapshot();
        Map<String, Object> validation = store.importFiles(List.of(fileWithIssue()), true);
        assertEquals(true, validation.get("dry_run"));
        assertTrue(oldSnapshot == store.snapshot());

        store.importFiles(List.of(file("first", "S.B::X", "S.NEW::X")), false);
        assertEquals(2, store.snapshot().documents().size());
        assertThrows(IllegalArgumentException.class, () -> store.snapshot().raw().node("table:S.A"));
        assertEquals(2, store.documents("", "", 0, 20).get("total"));
        assertEquals(1, store.documents("east", "first", 0, 20).get("total"));
        assertTrue(store.document("east:first", false).containsKey("content"));
        assertTrue(store.document("east:first", true).containsKey("content"));
        assertThrows(IllegalArgumentException.class, () -> store.document("missing", false));
        assertEquals("sqlite", store.backend());
        assertEquals(7, store.schemaVersion());
        assertTrue(store.tableNameMapper().mappings().isEmpty());
        assertTrue(store.nameDictionary().entries().isEmpty());
        String firstHash = store.snapshot().documents().get("east:first").hash();
        assertEquals(1, store.previewReassignment(
                List.of(new DocumentSelection("east:first", "stale")), BusinessLine.STANDARD).get("stale"));
        assertThrows(IllegalArgumentException.class, () -> store.previewReassignment(List.of(
                new DocumentSelection("east:first", firstHash), new DocumentSelection("east:first", firstHash)),
                BusinessLine.STANDARD));
        List<DocumentSelection> selected = List.of(new DocumentSelection("east:first", firstHash));
        Map<String, Object> preview = store.previewReassignment(selected, BusinessLine.STANDARD);
        assertEquals(true, preview.get("can_apply"));
        assertThrows(IllegalArgumentException.class,
                () -> store.reassign(selected, BusinessLine.STANDARD, "wrong-token"));
        assertEquals(1, store.reassign(selected, BusinessLine.STANDARD,
                String.valueOf(preview.get("preview_token"))).get("changed"));
        assertFalse(store.snapshot().documents().containsKey("east:first"));
        assertTrue(store.snapshot().documents().containsKey("standard:first"));
        assertEquals(1, store.previewReassignment(List.of(new DocumentSelection("standard:first", firstHash)),
                BusinessLine.STANDARD).get("unchanged"));
        store.close();

        LineageStore reopened = store(repository(database));
        assertEquals(2, reopened.snapshot().documents().size());
        assertEquals(1, reopened.deleteDocuments(new LineageStore.CollectionIds(List.of("standard:first"))).get("changed"));
        assertEquals(1, reopened.snapshot().documents().size());
        assertEquals(0, reopened.deleteDocuments(new LineageStore.CollectionIds(List.of("missing"))).get("changed"));
        reopened.close();
    }

    @Test
    public void duplicateBatchAndRepositoryFailureLeaveSnapshotUntouched() {
        LineageRepository repository = mock(LineageRepository.class);
        when(repository.loadTableNameMapper(any())).thenReturn(TableNameMapper.empty());
        when(repository.loadNameDictionary(any())).thenReturn(NameDictionary.empty());
        when(repository.loadDocuments()).thenReturn(List.of());
        when(repository.schemaVersion()).thenReturn(7);
        when(repository.backend()).thenReturn("sqlite");
        LineageStore store = store(repository);

        assertThrows(IllegalArgumentException.class, () -> store.importFiles(
                List.of(file("same", "S.B::X", "S.A::X"), file("same", "S.C::X", "S.B::X")), false));
        assertTrue(store.snapshot().documents().isEmpty());

        doThrow(new RuntimeException("simulated commit failure")).when(repository).replaceAll(any(), any(), any());
        assertThrows(RuntimeException.class,
                () -> store.importFiles(List.of(file("failed", "S.B::X", "S.A::X")), false));
        assertTrue(store.snapshot().documents().isEmpty());
        assertFalse(store.snapshot().mapped().stats().isEmpty());
    }

    private LineageStore store(LineageRepository repository) {
        LineageDocumentAdapter adapter = new LineageDocumentAdapter(new LineageJsonParser(),
                new LineageSchemaValidator(), new LineageSemanticValidator());
        return new LineageStore(repository, adapter, new DruidValueLineageDecider(),
                TableNameMapper.empty(), NameDictionary.empty());
    }

    private JdbcLineageRepository repository(Path database) {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + database.toAbsolutePath());
        return new JdbcLineageRepository(dataSource, "sqlite", "");
    }

    private ImportFile file(String id, String target, String source) {
        String content = "{\"schema_version\":\"1.0\",\"dataset_id\":\"" + id
                + "\",\"script_name\":\"sample.sql\",\"derivations\":[{\"target\":\"" + target
                + "\",\"sources\":[{\"kind\":\"column\",\"ref\":\"" + source
                + "\",\"path\":\"path\"}],\"transform_logic\":{\"path\":{\"expression\":\"S.X\"}}}]}";
        return new ImportFile(BusinessLine.EAST, id + ".json", content);
    }

    private ImportFile fileWithIssue() {
        String content = "{\"schema_version\":\"1.0\",\"dataset_id\":\"review\","
                + "\"script_name\":\"review.sql\",\"derivations\":[{\"target\":\"S.D::X\","
                + "\"sources\":[],\"transform_logic\":{\"path\":{\"expression\":\"1\"}}}]}";
        return new ImportFile(BusinessLine.EAST, "review.json", content);
    }
}
