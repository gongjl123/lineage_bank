package com.icbc.lineage.infrastructure.storage;

import com.alibaba.druid.pool.DruidDataSource;
import com.icbc.lineage.api.storage.LineageRepository;
import com.icbc.lineage.domain.catalog.NameDictionary;
import com.icbc.lineage.domain.catalog.TableNameMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.sqlite.SQLiteDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;

@Configuration
public class LineageStorageConfiguration {

    @Bean
    public DataSource lineageDataSource(
            @Value("${lineage.database.backend:sqlite}") String backend,
            @Value("${lineage.database.url:jdbc:sqlite:./var/lineage.sqlite3}") String url,
            @Value("${lineage.database.username:}") String username,
            @Value("${lineage.database.password:}") String password) {
        if ("sqlite".equalsIgnoreCase(backend)) {
            createSqliteParent(url);
            SQLiteDataSource dataSource = new SQLiteDataSource();
            dataSource.setUrl(url);
            return dataSource;
        }
        if (!"gaussdb".equalsIgnoreCase(backend)) {
            throw new IllegalArgumentException("Unsupported database backend: " + backend);
        }
        DruidDataSource dataSource = new DruidDataSource();
        dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setUrl(url);
        dataSource.setUsername(username);
        dataSource.setPassword(password);
        dataSource.setInitialSize(1);
        dataSource.setMinIdle(1);
        dataSource.setMaxActive(8);
        dataSource.setValidationQuery("SELECT 1");
        dataSource.setTestOnBorrow(true);
        dataSource.setTestWhileIdle(true);
        return dataSource;
    }

    @Bean(destroyMethod = "")
    public LineageRepository lineageRepository(DataSource lineageDataSource,
                                                @Value("${lineage.database.backend:sqlite}") String backend,
                                                @Value("${lineage.database.schema:lineage}") String schema) {
        return new JdbcLineageRepository(lineageDataSource, backend, schema);
    }

    @Bean
    public CatalogSeedLoader catalogSeedLoader(ObjectMapper objectMapper) {
        return new CatalogSeedLoader(objectMapper);
    }

    @Bean
    public TableNameMapper tableNameMapperSeed(CatalogSeedLoader loader,
                                                @Value("${lineage.catalog.mapping-file:}") String path) {
        return loader.tableMappings(path);
    }

    @Bean
    public NameDictionary nameDictionarySeed(CatalogSeedLoader loader,
                                               @Value("${lineage.catalog.name-dictionary-file:}") String path) {
        return loader.nameDictionary(path);
    }

    private void createSqliteParent(String url) {
        String prefix = "jdbc:sqlite:";
        if (!url.startsWith(prefix) || url.equals("jdbc:sqlite::memory:")) {
            return;
        }
        try {
            Path path = Path.of(url.substring(prefix.length())).toAbsolutePath();
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
        } catch (Exception exception) {
            throw new StorageException("Cannot create SQLite data directory", exception);
        }
    }
}
