package com.icbc.lineage.infrastructure.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.icbc.lineage.domain.catalog.NameDictionary;
import com.icbc.lineage.domain.catalog.TableNameMapper;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class testCatalogSeedLoader {

    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    private final CatalogSeedLoader loader = new CatalogSeedLoader(new ObjectMapper());

    @Test
    public void loadsV6WorkbookAndNameDictionary() throws Exception {
        Path workbook = temporary.newFile("mapping.xlsx").toPath();
        try (XSSFWorkbook value = new XSSFWorkbook()) {
            addMapping(value.createSheet("EAST湖表映射"), "ODS.SOURCE", "BUS.TARGET");
            addMapping(value.createSheet("一表通湖表映射"), "ODS.SOURCE", "BUS.TARGET");
            try (OutputStream output = Files.newOutputStream(workbook)) {
                value.write(output);
            }
        }
        TableNameMapper mapper = loader.tableMappings(workbook.toString());
        assertEquals("BUS.TARGET", mapper.mappedTable("ods.source"));
        assertEquals(2, mapper.sheets("ODS.SOURCE").size());
        assertEquals("excel", mapper.summary().get("storage_source"));

        Path dictionary = temporary.newFile("names.json").toPath();
        Files.writeString(dictionary, """
                {"format_version":2,"sources":[{"file":"source.xlsx"}],"entries":[{
                  "source_type":"east","table_name_en":"ACCOUNT","table_name_cn":"账户",
                  "field_name_en":"ID","field_name_cn":"编号","source_file":"source.xlsx",
                  "source_locations":["Sheet1!A2"]}]}
                """, StandardCharsets.UTF_8);
        NameDictionary names = loader.nameDictionary(dictionary.toString());
        assertEquals(1, names.entries().size());
        assertEquals("seed", names.summary().get("storage_source"));
    }

    @Test
    public void supportsEmptyConfigurationAndRejectsInvalidSeeds() throws Exception {
        assertTrue(loader.tableMappings(null).mappings().isEmpty());
        assertTrue(loader.nameDictionary(" ").entries().isEmpty());
        assertThrows(IllegalArgumentException.class,
                () -> loader.tableMappings(temporary.getRoot().toPath().resolve("missing.xlsx").toString()));

        Path missingSheet = workbook("EAST湖表映射", "ODS.A", "BUS.A");
        assertThrows(IllegalArgumentException.class, () -> loader.tableMappings(missingSheet.toString()));

        Path invalidRow = workbookWithBothSheets("INVALID", "BUS.A");
        assertThrows(IllegalArgumentException.class, () -> loader.tableMappings(invalidRow.toString()));

        Path conflict = temporary.newFile("conflict.xlsx").toPath();
        try (XSSFWorkbook value = new XSSFWorkbook()) {
            addMapping(value.createSheet("EAST湖表映射"), "ODS.A", "BUS.A");
            addMapping(value.createSheet("一表通湖表映射"), "ODS.A", "BUS.B");
            try (OutputStream output = Files.newOutputStream(conflict)) {
                value.write(output);
            }
        }
        assertThrows(IllegalArgumentException.class, () -> loader.tableMappings(conflict.toString()));

        Path version = temporary.newFile("version.json").toPath();
        Files.writeString(version, "{\"format_version\":1,\"entries\":[],\"sources\":[]}");
        assertThrows(IllegalArgumentException.class, () -> loader.nameDictionary(version.toString()));
        Path malformed = temporary.newFile("malformed.json").toPath();
        Files.writeString(malformed, "{");
        assertThrows(IllegalArgumentException.class, () -> loader.nameDictionary(malformed.toString()));
    }

    private Path workbook(String sheet, String source, String target) throws Exception {
        Path path = temporary.newFile(sheet + ".xlsx").toPath();
        try (XSSFWorkbook value = new XSSFWorkbook()) {
            addMapping(value.createSheet(sheet), source, target);
            try (OutputStream output = Files.newOutputStream(path)) {
                value.write(output);
            }
        }
        return path;
    }

    private Path workbookWithBothSheets(String source, String target) throws Exception {
        Path path = temporary.newFile("both.xlsx").toPath();
        try (XSSFWorkbook value = new XSSFWorkbook()) {
            addMapping(value.createSheet("EAST湖表映射"), source, target);
            addMapping(value.createSheet("一表通湖表映射"), "", "");
            try (OutputStream output = Files.newOutputStream(path)) {
                value.write(output);
            }
        }
        return path;
    }

    private void addMapping(Sheet sheet, String source, String target) {
        Row header = sheet.createRow(0);
        header.createCell(0).setCellValue("A表");
        header.createCell(1).setCellValue("B表");
        Row row = sheet.createRow(1);
        row.createCell(0).setCellValue(source);
        row.createCell(1).setCellValue(target);
    }
}
