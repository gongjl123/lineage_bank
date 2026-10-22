package com.icbc.lineage.infrastructure.storage;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.icbc.lineage.api.storage.ImportBatch;
import com.icbc.lineage.api.storage.LineageRepository;
import com.icbc.lineage.api.storage.StoredDocument;
import com.icbc.lineage.domain.catalog.NameDictionary;
import com.icbc.lineage.domain.catalog.TableNameMapper;
import com.icbc.lineage.domain.graph.LineageGraph;
import com.icbc.lineage.domain.model.LineageDocument;
import com.icbc.lineage.domain.model.LineageIssue;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Pattern;

/** JDBC storage shared by SQLite and GaussDB/PostgreSQL compatible drivers. */
public final class JdbcLineageRepository implements LineageRepository {

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final TypeReference<List<LineageIssue>> ISSUES = new TypeReference<>() { };
    private static final TypeReference<List<Map<String, Object>>> ROWS = new TypeReference<>() { };

    private final DataSource dataSource;
    private final ObjectMapper json;
    private final String backend;
    private final String schema;
    private final String prefix;

    public JdbcLineageRepository(DataSource dataSource, String backend, String schema) {
        this(dataSource, backend, schema, new ObjectMapper().findAndRegisterModules());
    }

    public JdbcLineageRepository(DataSource dataSource, String backend, String schema, ObjectMapper json) {
        this.dataSource = dataSource;
        this.backend = normalizeBackend(backend);
        this.schema = schema == null || schema.isBlank() ? "lineage" : schema;
        if (this.backend.equals("gaussdb") && !IDENTIFIER.matcher(this.schema).matches()) {
            throw new IllegalArgumentException("Invalid GaussDB schema name");
        }
        this.prefix = this.backend.equals("gaussdb") ? quote(this.schema) + "." : "";
        this.json = json;
        initialize();
    }

    @Override
    public String backend() {
        return backend;
    }

    @Override
    public int schemaVersion() {
        try (Connection connection = connection();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT version FROM " + table("schema_version"))) {
            if (!rows.next()) {
                throw new StorageException("Storage schema version is missing");
            }
            int version = rows.getInt(1);
            if (rows.next()) {
                throw new StorageException("Storage contains multiple schema versions");
            }
            return version;
        } catch (SQLException exception) {
            throw failure("Cannot read storage schema version", exception);
        }
    }

    @Override
    public List<StoredDocument> loadDocuments() {
        String sql = "SELECT id,line,filename,hash,original_key,normalized_key,issues,updated_at "
                + "FROM " + table("documents") + " ORDER BY id";
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rows = statement.executeQuery()) {
            List<StoredDocument> result = new ArrayList<>();
            while (rows.next()) {
                JsonNode original = json.readTree(payload(connection, rows.getString("original_key")));
                LineageDocument normalized = json.readValue(payload(connection, rows.getString("normalized_key")),
                        LineageDocument.class);
                List<LineageIssue> issues = json.readValue(rows.getString("issues"), ISSUES);
                Timestamp updated = rows.getTimestamp("updated_at");
                result.add(new StoredDocument(rows.getString("id"), rows.getString("line"),
                        rows.getString("filename"), rows.getString("hash"), original, normalized, issues,
                        updated == null ? Instant.EPOCH : updated.toInstant()));
            }
            return List.copyOf(result);
        } catch (SQLException | java.io.IOException exception) {
            throw failure("Cannot load lineage documents", exception);
        }
    }

    @Override
    public TableNameMapper loadTableNameMapper(TableNameMapper fallback) {
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            try {
                TableNameMapper result;
                try (PreparedStatement query = connection.prepareStatement(
                        "SELECT workbook,fingerprint,initialized_at FROM " + table("table_mapping_config") + " WHERE id=1");
                     ResultSet config = query.executeQuery()) {
                    if (!config.next()) {
                        initializeMappings(connection, fallback);
                        connection.commit();
                        return fallback;
                    }
                    Map<String, String> mappings = new LinkedHashMap<>();
                    Map<String, Collection<String>> sheets = new LinkedHashMap<>();
                    try (PreparedStatement rows = connection.prepareStatement("SELECT source_table,target_table,source_sheets FROM "
                            + table("fsis_table_mappings") + " ORDER BY source_table"); ResultSet values = rows.executeQuery()) {
                        while (values.next()) {
                            mappings.put(values.getString(1), values.getString(2));
                            sheets.put(values.getString(1), json.readValue(values.getString(3), new TypeReference<List<String>>() { }));
                        }
                    }
                    Timestamp initialized = config.getTimestamp(3);
                    result = new TableNameMapper(mappings, sheets, config.getString(1), config.getString(2),
                            "database", initialized == null ? null : initialized.toInstant().toString());
                }
                connection.commit();
                return result;
            } catch (Exception exception) {
                rollback(connection, exception);
                throw exception;
            }
        } catch (Exception exception) {
            throw failure("Cannot load table mappings", exception);
        }
    }

    @Override
    public NameDictionary loadNameDictionary(NameDictionary fallback) {
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement query = connection.prepareStatement(
                        "SELECT sources,initialized_at FROM " + table("name_dictionary_config") + " WHERE id=1");
                     ResultSet config = query.executeQuery()) {
                    if (!config.next()) {
                        initializeNames(connection, fallback);
                        connection.commit();
                        return fallback;
                    }
                    List<Map<String, Object>> sources = json.readValue(config.getString(1), ROWS);
                    List<Map<String, Object>> entries = new ArrayList<>();
                    try (PreparedStatement rows = connection.prepareStatement("SELECT source_type,table_name_en,table_name_cn,"
                            + "field_name_en,field_name_cn,source_file,source_locations FROM " + table("name_dictionary")
                            + " ORDER BY source_type,table_name_en,field_name_en"); ResultSet values = rows.executeQuery()) {
                        while (values.next()) {
                            entries.add(result("source_type", values.getString(1), "table_name_en", values.getString(2),
                                    "table_name_cn", values.getString(3), "field_name_en", values.getString(4),
                                    "field_name_cn", values.getString(5), "source_file", values.getString(6),
                                    "source_locations", json.readValue(values.getString(7), new TypeReference<List<String>>() { })));
                        }
                    }
                    Timestamp initialized = config.getTimestamp(2);
                    NameDictionary result = new NameDictionary(entries, sources, "database",
                            initialized == null ? null : initialized.toInstant().toString());
                    connection.commit();
                    return result;
                }
            } catch (Exception exception) {
                rollback(connection, exception);
                throw exception;
            }
        } catch (Exception exception) {
            throw failure("Cannot load name dictionary", exception);
        }
    }

    @Override
    public void replaceAll(List<StoredDocument> documents, LineageGraph graph, ImportBatch batch) {
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            try {
                deleteProjection(connection);
                insertDocuments(connection, documents);
                insertEntities(connection, graph.entityRecords());
                insertDerivations(connection, graph.derivationRecords());
                insertRelations(connection, graph.relationRecordsForStorage());
                insertIssues(connection, graph.issues());
                insertBatch(connection, batch);
                connection.commit();
            } catch (Exception exception) {
                rollback(connection, exception);
                throw exception;
            }
        } catch (Exception exception) {
            throw failure("Cannot commit lineage import", exception);
        }
    }

    @Override
    public void close() {
        if (dataSource instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception exception) {
                throw failure("Cannot close data source", exception);
            }
        }
    }

    private void initialize() {
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            if (backend.equals("gaussdb")) {
                statement.execute("CREATE SCHEMA IF NOT EXISTS " + quote(schema));
            }
            String blob = backend.equals("gaussdb") ? "BYTEA" : "BLOB";
            String timestamp = backend.equals("gaussdb") ? "TIMESTAMPTZ" : "TIMESTAMP";
            execute(statement,
                    "CREATE TABLE IF NOT EXISTS " + table("schema_version") + " (version INTEGER PRIMARY KEY, created_at " + timestamp + " NOT NULL DEFAULT CURRENT_TIMESTAMP)",
                    "CREATE TABLE IF NOT EXISTS " + table("payloads") + " (key TEXT PRIMARY KEY, data " + blob + " NOT NULL)",
                    "CREATE TABLE IF NOT EXISTS " + table("documents") + " (id TEXT PRIMARY KEY,line TEXT NOT NULL,filename TEXT NOT NULL,hash TEXT NOT NULL,original_key TEXT NOT NULL REFERENCES " + table("payloads") + "(key),normalized_key TEXT NOT NULL REFERENCES " + table("payloads") + "(key),issues TEXT NOT NULL,updated_at " + timestamp + " NOT NULL DEFAULT CURRENT_TIMESTAMP)",
                    "CREATE TABLE IF NOT EXISTS " + table("import_batches") + " (id TEXT PRIMARY KEY,changed INTEGER NOT NULL,unchanged INTEGER NOT NULL,input_count INTEGER NOT NULL,elapsed_millis BIGINT NOT NULL,created_at " + timestamp + " NOT NULL)",
                    "CREATE TABLE IF NOT EXISTS " + table("entities") + " (id TEXT PRIMARY KEY,type TEXT NOT NULL,parent_id TEXT REFERENCES " + table("entities") + "(id),detail TEXT NOT NULL)",
                    "CREATE TABLE IF NOT EXISTS " + table("derivations") + " (id TEXT PRIMARY KEY,document_id TEXT NOT NULL REFERENCES " + table("documents") + "(id),ordinal INTEGER NOT NULL,target TEXT NOT NULL REFERENCES " + table("entities") + "(id),detail TEXT NOT NULL,UNIQUE(document_id,ordinal))",
                    "CREATE TABLE IF NOT EXISTS " + table("relations") + " (id TEXT PRIMARY KEY,document_id TEXT NOT NULL REFERENCES " + table("documents") + "(id),derivation_id TEXT NOT NULL REFERENCES " + table("derivations") + "(id),source TEXT NOT NULL REFERENCES " + table("entities") + "(id),target TEXT NOT NULL REFERENCES " + table("entities") + "(id),source_ordinal INTEGER NOT NULL,path TEXT NOT NULL,business_tag TEXT NOT NULL,detail TEXT NOT NULL,UNIQUE(derivation_id,source_ordinal))",
                    "CREATE TABLE IF NOT EXISTS " + table("issues") + " (id TEXT PRIMARY KEY,code TEXT NOT NULL,severity TEXT NOT NULL,document_id TEXT,location TEXT NOT NULL,message TEXT NOT NULL,data TEXT NOT NULL)",
                    "CREATE TABLE IF NOT EXISTS " + table("table_mapping_config") + " (id INTEGER PRIMARY KEY,workbook TEXT NOT NULL,fingerprint TEXT NOT NULL,initialized_at " + timestamp + " NOT NULL DEFAULT CURRENT_TIMESTAMP)",
                    "CREATE TABLE IF NOT EXISTS " + table("fsis_table_mappings") + " (source_table TEXT PRIMARY KEY,target_table TEXT NOT NULL,source_sheets TEXT NOT NULL)",
                    "CREATE TABLE IF NOT EXISTS " + table("name_dictionary_config") + " (id INTEGER PRIMARY KEY,sources TEXT NOT NULL,initialized_at " + timestamp + " NOT NULL DEFAULT CURRENT_TIMESTAMP)",
                    "CREATE TABLE IF NOT EXISTS " + table("name_dictionary") + " (source_type TEXT NOT NULL,table_name_en TEXT NOT NULL,table_name_cn TEXT NOT NULL,field_name_en TEXT NOT NULL,field_name_cn TEXT NOT NULL,source_file TEXT NOT NULL,source_locations TEXT NOT NULL,PRIMARY KEY(source_type,table_name_en,field_name_en))");
            try (ResultSet rows = statement.executeQuery("SELECT version FROM " + table("schema_version"))) {
                if (!rows.next()) {
                    statement.executeUpdate("INSERT INTO " + table("schema_version") + "(version) VALUES (" + SCHEMA_VERSION + ")");
                } else if (rows.getInt(1) != SCHEMA_VERSION || rows.next()) {
                    throw new StorageException("Unsupported storage schema version; expected " + SCHEMA_VERSION);
                }
            }
            try (ResultSet ignored = statement.executeQuery(
                    "SELECT normalized_key FROM " + table("documents") + " WHERE 1=0")) {
                // Java V6 stores the normalized document payload by key. Python V6 used graph_data.
            } catch (SQLException incompatibleLayout) {
                throw new StorageException("Existing database uses the Python V6 layout; create a new Java database "
                        + "and reimport the source JSON or offline intermediate results", incompatibleLayout);
            }
        } catch (SQLException exception) {
            throw failure("Cannot initialize lineage storage", exception);
        }
    }

    private void initializeMappings(Connection connection, TableNameMapper fallback) throws SQLException, JsonProcessingException {
        try (PreparedStatement config = connection.prepareStatement("INSERT INTO " + table("table_mapping_config")
                + "(id,workbook,fingerprint) VALUES (1,?,?)")) {
            Map<String, Object> summary = fallback.summary();
            config.setString(1, text(summary.get("workbook")));
            config.setString(2, text(summary.get("fingerprint")));
            config.executeUpdate();
        }
        try (PreparedStatement row = connection.prepareStatement("INSERT INTO " + table("fsis_table_mappings")
                + "(source_table,target_table,source_sheets) VALUES (?,?,?)")) {
            for (Map.Entry<String, String> item : fallback.mappings().entrySet()) {
                row.setString(1, item.getKey());
                row.setString(2, item.getValue());
                row.setString(3, json.writeValueAsString(fallback.sheets(item.getKey())));
                row.addBatch();
            }
            row.executeBatch();
        }
    }

    @SuppressWarnings("unchecked")
    private void initializeNames(Connection connection, NameDictionary fallback) throws SQLException, JsonProcessingException {
        Map<String, Object> summary = fallback.summary();
        try (PreparedStatement config = connection.prepareStatement("INSERT INTO " + table("name_dictionary_config")
                + "(id,sources) VALUES (1,?)")) {
            config.setString(1, json.writeValueAsString(summary.getOrDefault("sources", List.of())));
            config.executeUpdate();
        }
        try (PreparedStatement row = connection.prepareStatement("INSERT INTO " + table("name_dictionary")
                + "(source_type,table_name_en,table_name_cn,field_name_en,field_name_cn,source_file,source_locations) VALUES (?,?,?,?,?,?,?)")) {
            for (Map<String, Object> item : fallback.entries()) {
                row.setString(1, text(item.get("source_type")));
                row.setString(2, text(item.get("table_name_en")));
                row.setString(3, text(item.get("table_name_cn")));
                row.setString(4, text(item.get("field_name_en")));
                row.setString(5, text(item.get("field_name_cn")));
                row.setString(6, text(item.get("source_file")));
                row.setString(7, json.writeValueAsString(item.getOrDefault("source_locations", List.of())));
                row.addBatch();
            }
            row.executeBatch();
        }
    }

    private void deleteProjection(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            for (String name : List.of("relations", "derivations", "issues", "entities", "documents", "payloads")) {
                statement.executeUpdate("DELETE FROM " + table(name));
            }
        }
    }

    private void insertDocuments(Connection connection, List<StoredDocument> documents) throws SQLException, JsonProcessingException {
        String payloadSql = "INSERT INTO " + table("payloads") + "(key,data) VALUES (?,?)";
        String documentSql = "INSERT INTO " + table("documents")
                + "(id,line,filename,hash,original_key,normalized_key,issues,updated_at) VALUES (?,?,?,?,?,?,?,?)";
        try (PreparedStatement payload = connection.prepareStatement(payloadSql);
             PreparedStatement document = connection.prepareStatement(documentSql)) {
            for (StoredDocument item : documents) {
                String originalKey = "original:" + item.id() + ":" + item.hash();
                String normalizedKey = "normalized:" + item.id() + ":" + item.hash();
                addPayload(payload, originalKey, json.writeValueAsBytes(item.original()));
                addPayload(payload, normalizedKey, json.writeValueAsBytes(item.normalized()));
                document.setString(1, item.id());
                document.setString(2, item.line());
                document.setString(3, item.filename());
                document.setString(4, item.hash());
                document.setString(5, originalKey);
                document.setString(6, normalizedKey);
                document.setString(7, json.writeValueAsString(item.issues()));
                document.setTimestamp(8, Timestamp.from(item.updatedAt()));
                document.addBatch();
            }
            payload.executeBatch();
            document.executeBatch();
        }
    }

    private void addPayload(PreparedStatement payload, String key, byte[] data) throws SQLException {
        payload.setString(1, key);
        payload.setBytes(2, data);
        payload.addBatch();
    }

    private void insertEntities(Connection connection, List<Map<String, Object>> entities) throws SQLException, JsonProcessingException {
        List<Map<String, Object>> ordered = entities.stream().sorted(Comparator.comparingInt(
                item -> "column".equals(item.get("type")) ? 1 : 0)).toList();
        try (PreparedStatement row = connection.prepareStatement("INSERT INTO " + table("entities")
                + "(id,type,parent_id,detail) VALUES (?,?,?,?)")) {
            for (Map<String, Object> item : ordered) {
                row.setString(1, text(item.get("id")));
                row.setString(2, text(item.get("type")));
                row.setString(3, nullable(item.get("parent_id")));
                row.setString(4, json.writeValueAsString(item));
                row.addBatch();
            }
            row.executeBatch();
        }
    }

    private void insertDerivations(Connection connection, List<Map<String, Object>> values) throws SQLException, JsonProcessingException {
        try (PreparedStatement row = connection.prepareStatement("INSERT INTO " + table("derivations")
                + "(id,document_id,ordinal,target,detail) VALUES (?,?,?,?,?)")) {
            for (Map<String, Object> item : values) {
                row.setString(1, text(item.get("id")));
                row.setString(2, text(item.get("document")));
                row.setInt(3, number(item.get("index")));
                row.setString(4, text(item.get("target")));
                row.setString(5, json.writeValueAsString(item));
                row.addBatch();
            }
            row.executeBatch();
        }
    }

    private void insertRelations(Connection connection, List<Map<String, Object>> values) throws SQLException, JsonProcessingException {
        try (PreparedStatement row = connection.prepareStatement("INSERT INTO " + table("relations")
                + "(id,document_id,derivation_id,source,target,source_ordinal,path,business_tag,detail) VALUES (?,?,?,?,?,?,?,?,?)")) {
            for (Map<String, Object> item : values) {
                row.setString(1, text(item.get("id")));
                row.setString(2, text(item.get("document")));
                row.setString(3, text(item.get("derivation_id")));
                row.setString(4, text(item.get("source")));
                row.setString(5, text(item.get("target")));
                row.setInt(6, number(item.get("source_index")));
                row.setString(7, text(item.get("path")));
                row.setString(8, businessTag(item));
                row.setString(9, json.writeValueAsString(item));
                row.addBatch();
            }
            row.executeBatch();
        }
    }

    private void insertIssues(Connection connection, List<LineageIssue> values) throws SQLException, JsonProcessingException {
        try (PreparedStatement row = connection.prepareStatement("INSERT INTO " + table("issues")
                + "(id,code,severity,document_id,location,message,data) VALUES (?,?,?,?,?,?,?)")) {
            int index = 0;
            for (LineageIssue item : values) {
                row.setString(1, digestId(item.code(), item.document(), item.location(), index++));
                row.setString(2, item.code());
                row.setString(3, item.severity().name().toLowerCase(Locale.ROOT));
                row.setString(4, nullable(item.document()));
                row.setString(5, text(item.location()));
                row.setString(6, item.message());
                row.setString(7, json.writeValueAsString(item.data()));
                row.addBatch();
            }
            row.executeBatch();
        }
    }

    private void insertBatch(Connection connection, ImportBatch batch) throws SQLException {
        try (PreparedStatement row = connection.prepareStatement("INSERT INTO " + table("import_batches")
                + "(id,changed,unchanged,input_count,elapsed_millis,created_at) VALUES (?,?,?,?,?,?)")) {
            row.setString(1, batch.id());
            row.setInt(2, batch.changed());
            row.setInt(3, batch.unchanged());
            row.setInt(4, batch.inputCount());
            row.setLong(5, batch.elapsedMillis());
            row.setTimestamp(6, Timestamp.from(batch.createdAt()));
            row.executeUpdate();
        }
    }

    private byte[] payload(Connection connection, String key) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT data FROM " + table("payloads") + " WHERE key=?")) {
            statement.setString(1, key);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new StorageException("Missing payload: " + key);
                }
                return rows.getBytes(1);
            }
        }
    }

    private Connection connection() throws SQLException {
        Connection connection = dataSource.getConnection();
        if (backend.equals("sqlite")) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA foreign_keys=ON");
            }
        }
        return connection;
    }

    private void execute(Statement statement, String... sql) throws SQLException {
        for (String value : sql) {
            statement.execute(value);
        }
    }

    private void rollback(Connection connection, Exception original) {
        try {
            connection.rollback();
        } catch (SQLException rollback) {
            original.addSuppressed(rollback);
        }
    }

    private String table(String name) {
        if (!List.of("schema_version", "documents", "payloads", "import_batches", "entities", "derivations",
                "relations", "issues", "fsis_table_mappings", "table_mapping_config", "name_dictionary",
                "name_dictionary_config").contains(name)) {
            throw new IllegalArgumentException("Unknown lineage table");
        }
        return prefix + quote(name);
    }

    private static String quote(String identifier) {
        return "\"" + identifier + "\"";
    }

    private static String normalizeBackend(String backend) {
        String value = backend == null ? "sqlite" : backend.trim().toLowerCase(Locale.ROOT);
        if (!value.equals("sqlite") && !value.equals("gaussdb")) {
            throw new IllegalArgumentException("Unsupported database backend: " + value);
        }
        return value;
    }

    private static String businessTag(Map<String, Object> relation) {
        Object logic = relation.get("logic");
        if (logic instanceof Map<?, ?> values) {
            for (String key : List.of("YWGSBM", "YSGSBM", "BIZ_LABEL")) {
                for (Map.Entry<?, ?> item : values.entrySet()) {
                    if (key.equalsIgnoreCase(text(item.getKey()))) {
                        return text(item.getValue());
                    }
                }
            }
        }
        return "";
    }

    private static String digestId(Object... values) {
        return com.icbc.lineage.common.DigestUtils.sha256(java.util.Arrays.stream(values)
                .map(JdbcLineageRepository::text).reduce((left, right) -> left + "\0" + right).orElse(""));
    }

    private static int number(Object value) {
        return value instanceof Number number ? number.intValue() : Integer.parseInt(text(value));
    }

    private static String nullable(Object value) {
        return value == null || text(value).isBlank() ? null : text(value);
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private StorageException failure(String message, Exception exception) {
        return exception instanceof StorageException storage ? storage : new StorageException(message, exception);
    }

    private static Map<String, Object> result(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            result.put(String.valueOf(values[index]), values[index + 1]);
        }
        return result;
    }
}
