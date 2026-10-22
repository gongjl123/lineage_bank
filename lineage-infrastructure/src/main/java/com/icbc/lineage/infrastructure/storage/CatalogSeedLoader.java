package com.icbc.lineage.infrastructure.storage;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.icbc.lineage.common.DigestUtils;
import com.icbc.lineage.domain.catalog.NameDictionary;
import com.icbc.lineage.domain.catalog.TableNameMapper;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Loads the V6 mapping workbook and name dictionary during first database initialization. */
public final class CatalogSeedLoader {

    private static final List<String> MAPPING_SHEETS = List.of("EAST湖表映射", "一表通湖表映射");
    private static final TypeReference<List<Map<String, Object>>> ROWS = new TypeReference<>() { };

    private final ObjectMapper json;

    public CatalogSeedLoader(ObjectMapper json) {
        this.json = json;
    }

    public TableNameMapper tableMappings(String configuredPath) {
        if (configuredPath == null || configuredPath.isBlank()) {
            return TableNameMapper.empty();
        }
        Path path = requiredFile(configuredPath, "Table mapping workbook");
        Map<String, String> mappings = new LinkedHashMap<>();
        Map<String, Set<String>> sheets = new LinkedHashMap<>();
        DataFormatter formatter = new DataFormatter(Locale.ROOT);
        try (InputStream input = Files.newInputStream(path); Workbook workbook = WorkbookFactory.create(input)) {
            for (String sheetName : MAPPING_SHEETS) {
                Sheet sheet = workbook.getSheet(sheetName);
                if (sheet == null) {
                    throw new IllegalArgumentException("Table mapping workbook is missing sheet: " + sheetName);
                }
                for (int index = 1; index <= sheet.getLastRowNum(); index++) {
                    Row row = sheet.getRow(index);
                    String source = cell(formatter, row, 0);
                    String target = cell(formatter, row, 1);
                    if (source.isEmpty() && target.isEmpty()) {
                        continue;
                    }
                    if (!validTable(source) || !validTable(target)) {
                        throw new IllegalArgumentException("Invalid table mapping at " + sheetName + " row " + (index + 1));
                    }
                    String previous = mappings.putIfAbsent(source, target);
                    if (previous != null && !previous.equals(target)) {
                        throw new IllegalArgumentException("Conflicting table mapping for " + source);
                    }
                    sheets.computeIfAbsent(source, key -> new LinkedHashSet<>()).add(sheetName);
                }
            }
            return new TableNameMapper(mappings, sheets, path.getFileName().toString(),
                    DigestUtils.sha256(Files.readAllBytes(path)), "excel", null);
        } catch (IOException exception) {
            throw new IllegalArgumentException("Cannot read table mapping workbook: " + path, exception);
        }
    }

    public NameDictionary nameDictionary(String configuredPath) {
        if (configuredPath == null || configuredPath.isBlank()) {
            return NameDictionary.empty();
        }
        Path path = requiredFile(configuredPath, "Name dictionary seed");
        try {
            JsonNode root = json.readTree(Files.readAllBytes(path));
            if (root.path("format_version").asInt() != 2 || !root.path("entries").isArray()
                    || !root.path("sources").isArray()) {
                throw new IllegalArgumentException("Name dictionary seed format must be version 2");
            }
            List<Map<String, Object>> entries = json.convertValue(root.path("entries"), ROWS);
            List<Map<String, Object>> sources = json.convertValue(root.path("sources"), ROWS);
            return new NameDictionary(entries, sources, "seed", null);
        } catch (IOException exception) {
            throw new IllegalArgumentException("Cannot read name dictionary seed: " + path, exception);
        }
    }

    private Path requiredFile(String value, String label) {
        Path path = Path.of(value).toAbsolutePath().normalize();
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException(label + " does not exist: " + path);
        }
        return path;
    }

    private String cell(DataFormatter formatter, Row row, int index) {
        return row == null ? "" : formatter.formatCellValue(row.getCell(index)).trim().toUpperCase(Locale.ROOT);
    }

    private boolean validTable(String value) {
        return value.indexOf('.') > 0 && !value.endsWith(".") && !value.contains("::");
    }
}
