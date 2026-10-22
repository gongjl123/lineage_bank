package com.icbc.lineage.api.storage;

import com.icbc.lineage.domain.catalog.NameDictionary;
import com.icbc.lineage.domain.catalog.TableNameMapper;
import com.icbc.lineage.domain.graph.LineageGraph;

import java.util.List;

public interface LineageRepository extends AutoCloseable {

    int SCHEMA_VERSION = 7;

    String backend();

    int schemaVersion();

    List<StoredDocument> loadDocuments();

    TableNameMapper loadTableNameMapper(TableNameMapper fallback);

    NameDictionary loadNameDictionary(NameDictionary fallback);

    void replaceAll(List<StoredDocument> documents, LineageGraph graph, ImportBatch batch);

    @Override
    void close();
}
