package com.icbc.lineage.infrastructure.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.icbc.lineage.api.storage.ImportBatch;
import com.icbc.lineage.api.storage.StoredDocument;
import com.icbc.lineage.domain.catalog.NameDictionary;
import com.icbc.lineage.domain.catalog.TableNameMapper;
import com.icbc.lineage.domain.graph.LineageGraph;
import com.icbc.lineage.domain.model.Derivation;
import com.icbc.lineage.domain.model.LineageDocument;
import com.icbc.lineage.domain.model.LineageIssue;
import com.icbc.lineage.domain.model.LineageSource;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class testJdbcLineageRepository {

    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void persistsVersionSevenDocumentsProjectionAndConfiguration() throws Exception {
        Path database = temporary.newFile("lineage.sqlite3").toPath();
        SQLiteDataSource dataSource = dataSource(database);
        ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        TableNameMapper mapper = new TableNameMapper(Map.of("SRC.A", "BUS.B"),
                Map.of("SRC.A", List.of("mapping")), "mapping.xlsx", "sha", "seed", null);
        NameDictionary dictionary = new NameDictionary(List.of(Map.of(
                "source_type", "east", "table_name_en", "JGXXB", "table_name_cn", "机构表",
                "field_name_en", "ID", "field_name_cn", "编号", "source_file", "names.json",
                "source_locations", List.of("row:1"))), List.of(Map.of("file", "names.json")), "seed", null);

        JdbcLineageRepository repository = new JdbcLineageRepository(dataSource, "sqlite", "", json);
        assertEquals(7, repository.schemaVersion());
        assertEquals("sqlite", repository.backend());
        assertEquals("BUS.B", repository.loadTableNameMapper(mapper).mappedTable("SRC.A"));
        assertEquals(1, repository.loadNameDictionary(dictionary).entries().size());

        LineageDocument document = document("east:sample", "S.B::X", "S.A::X");
        LineageIssue issue = LineageIssue.of("NOTICE", "message", document.datasetId(), "location",
                LineageIssue.Severity.INFO);
        LineageGraph graph = new LineageGraph(List.of(document), List.of(issue));
        StoredDocument stored = new StoredDocument(document.datasetId(), "east", "sample.json", "hash",
                json.readTree("{\"schema_version\":\"1.0\"}"), document, List.of(issue), Instant.now());
        repository.replaceAll(List.of(stored), graph,
                new ImportBatch("batch-1", 1, 0, 1, 12, Instant.now()));
        List<StoredDocument> loaded = repository.loadDocuments();
        assertEquals(1, loaded.size());
        assertEquals(document, loaded.get(0).normalized());
        assertEquals("NOTICE", loaded.get(0).issues().get(0).code());
        assertEquals(1, count(dataSource, "relations"));
        assertEquals(1, count(dataSource, "derivations"));
        assertTrue(count(dataSource, "entities") >= 4);
        assertThrows(StorageException.class, () -> repository.replaceAll(List.of(), graph,
                new ImportBatch("broken", 1, 0, 1, 1, Instant.now())));
        assertEquals(1, repository.loadDocuments().size());
        repository.close();

        JdbcLineageRepository reopened = new JdbcLineageRepository(dataSource(database), "sqlite", "ignored");
        assertEquals("BUS.B", reopened.loadTableNameMapper(TableNameMapper.empty()).mappedTable("SRC.A"));
        assertEquals("编号", reopened.loadNameDictionary(NameDictionary.empty()).entries().get(0).get("field_name_cn"));
        assertEquals(1, reopened.loadDocuments().size());

        LineageDocument replacement = document("east:sample", "S.B::X", "S.NEW::X");
        StoredDocument updated = new StoredDocument(replacement.datasetId(), "east", "sample.json", "new-hash",
                json.readTree("{}"), replacement, List.of(), Instant.now());
        reopened.replaceAll(List.of(updated), new LineageGraph(List.of(replacement), List.of()),
                new ImportBatch("batch-2", 1, 0, 1, 4, Instant.now()));
        assertEquals("new-hash", reopened.loadDocuments().get(0).hash());
        assertEquals(2, count(dataSource(database), "import_batches"));
        reopened.close();
    }

    @Test
    public void validatesBackendSchemaAndMissingPayloads() throws Exception {
        SQLiteDataSource dataSource = dataSource(temporary.newFile("errors.sqlite3").toPath());
        assertThrows(IllegalArgumentException.class,
                () -> new JdbcLineageRepository(dataSource, "unknown", "lineage"));
        assertThrows(IllegalArgumentException.class,
                () -> new JdbcLineageRepository(dataSource, "gaussdb", "bad-name"));
        StorageException exception = new StorageException("message");
        assertEquals("message", exception.getMessage());
        assertFalse(exception.getMessage().isBlank());
    }

    @Test
    public void rejectsPythonVersionSevenLayoutWithMigrationGuidance() throws Exception {
        SQLiteDataSource dataSource = dataSource(temporary.newFile("python-v6.sqlite3").toPath());
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE schema_version(version INTEGER PRIMARY KEY, created_at TEXT)");
            statement.execute("INSERT INTO schema_version(version) VALUES (7)");
            statement.execute("CREATE TABLE payloads(key TEXT PRIMARY KEY,data BLOB NOT NULL)");
            statement.execute("CREATE TABLE documents(id TEXT PRIMARY KEY,line TEXT NOT NULL,filename TEXT NOT NULL,"
                    + "hash TEXT NOT NULL,script_name TEXT NOT NULL,original_key TEXT NOT NULL,graph_data BLOB NOT NULL,"
                    + "issues BLOB NOT NULL,updated_at TEXT)");
            statement.execute("CREATE TABLE import_batches(id INTEGER PRIMARY KEY,changed INTEGER,unchanged INTEGER,"
                    + "documents INTEGER,elapsed_seconds REAL,created_at TEXT)");
        }
        StorageException exception = assertThrows(StorageException.class,
                () -> new JdbcLineageRepository(dataSource, "sqlite", ""));
        assertTrue(exception.getMessage().contains("Python V6"));
    }

    private SQLiteDataSource dataSource(Path path) {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + path.toAbsolutePath());
        return dataSource;
    }

    private int count(SQLiteDataSource dataSource, String table) throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT count(*) FROM \"" + table + "\"")) {
            rows.next();
            return rows.getInt(1);
        }
    }

    private LineageDocument document(String id, String target, String source) {
        Derivation derivation = new Derivation(target, List.of(LineageSource.column(source, "path")),
                Map.of("path", Map.of("expression", "S.X")), Map.of());
        return new LineageDocument("1.0", id, "sample.sql", List.of(derivation),
                Map.of("business_line", "east"));
    }
}
