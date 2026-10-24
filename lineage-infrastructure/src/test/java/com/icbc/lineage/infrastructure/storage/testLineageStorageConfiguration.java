package com.icbc.lineage.infrastructure.storage;

import com.alibaba.druid.pool.DruidDataSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.icbc.lineage.api.storage.LineageRepository;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.sqlite.SQLiteDataSource;

import javax.sql.DataSource;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class testLineageStorageConfiguration {

    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void createsConfiguredStorageBeansForSupportedBackends() throws Exception {
        LineageStorageConfiguration configuration = new LineageStorageConfiguration();
        Path database = temporary.getRoot().toPath().resolve("nested/lineage.sqlite3");
        DataSource sqlite = configuration.lineageDataSource("sqlite", "jdbc:sqlite:" + database, "", "");
        assertTrue(sqlite instanceof SQLiteDataSource);
        assertTrue(configuration.lineageDataSource("sqlite", "jdbc:sqlite::memory:", "", "")
                instanceof SQLiteDataSource);
        LineageRepository repository = configuration.lineageRepository(sqlite, "sqlite", "lineage");
        assertEquals(7, repository.schemaVersion());
        repository.close();
        CatalogSeedLoader loader = configuration.catalogSeedLoader(new ObjectMapper());
        assertTrue(configuration.tableNameMapperSeed(loader, "").mappings().isEmpty());
        assertTrue(configuration.nameDictionarySeed(loader, "").entries().isEmpty());

        DruidDataSource gauss = (DruidDataSource) configuration.lineageDataSource("gaussdb",
                "jdbc:postgresql://127.0.0.1:5432/lineage", "user", "password");
        assertEquals("org.postgresql.Driver", gauss.getDriverClassName());
        assertEquals(8, gauss.getMaxActive());
        assertTrue(gauss.isTestOnBorrow());
        gauss.close();
        assertThrows(IllegalArgumentException.class,
                () -> configuration.lineageDataSource("other", "jdbc:none", "", ""));
    }
}
