package com.icbc.lineage.domain.catalog;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class testCatalogMetadata {

    @Test
    public void mapsTableNamesAndBuildsCatalogMetadata() {
        TableNameMapper mapper = new TableNameMapper(
                Map.of("src.a", "bus.b", "SRC.C", "BUS.B", "SRC.SAME", "SRC.SAME"),
                Map.of("SRC.A", List.of("sheet-b", "sheet-a", "sheet-a")),
                "mapping.xlsx", "file-sha", "database", "2026-10-21T00:00:00Z");

        assertEquals("BUS.B", mapper.mappedTable("src.a"));
        assertEquals("WITHOUT", mapper.displayEntityId("table:without"));
        assertEquals("BUS.B::ID", mapper.displayEntityId("column:SRC.A::ID"));
        assertEquals("value", mapper.displayEntityId("other:value"));
        assertEquals("raw", mapper.displayEntityId("raw"));
        Map<String, Object> table = mapper.decorate(node("table:SRC.A", "table", "SRC.A", "A"));
        assertEquals("BUS.B", table.get("display_qualified_name"));
        assertEquals(2, table.get("mapped_source_count"));
        assertEquals(List.of("sheet-a", "sheet-b"), table.get("mapping_sheets"));
        Map<String, Object> column = mapper.decorate(node("column:SRC.A::ID", "column", "SRC.A::ID", "ID"));
        assertEquals("BUS.B::ID", column.get("display_qualified_name"));
        assertTrue(mapper.matches(column, "bus.b"));
        Map<String, Object> aliased = new LinkedHashMap<>(column);
        aliased.put("original_names", List.of("OLD.SYSTEM::FIELD"));
        assertTrue(mapper.matches(aliased, "old.system"));
        assertTrue(mapper.matches(column, ""));
        assertFalse(mapper.matches(column, "missing"));

        Map<String, Object> constant = mapper.decorate(node("constant:x", "constant", "CONST:x", "x"));
        assertEquals("", constant.get("display_table"));
        Map<String, Object> summary = mapper.summary();
        assertEquals(3, summary.get("unique_sources"));
        assertEquals(2L, summary.get("changed_sources"));
        assertEquals(1L, summary.get("duplicate_targets"));
        assertEquals(List.of("sheet-a", "sheet-b"), mapper.sheets("src.a"));
        assertEquals(3, mapper.mappings().size());
        Map<String, Object> catalog = mapper.catalog("bus.b", 1, 1);
        assertEquals(2, catalog.get("total"));
        assertEquals(1, ((List<?>) catalog.get("items")).size());
        assertFalse(String.valueOf(summary.get("configuration_fingerprint")).isBlank());
        assertTrue(TableNameMapper.empty().mappings().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> new TableNameMapper(
                Map.of("SRC.A", "BUS.B", "BUS.B", "BUS.C"), Map.of(), "", "", "memory", null));
    }

    @Test
    public void resolvesChineseNamesWithoutChangingIdentifiers() {
        NameDictionary dictionary = new NameDictionary(List.of(
                entry("east", "JGXXB", "NBJGH", "机构信息表", "内部机构号"),
                entry("ybt", "YBT_T_10_20", "NBJGH", "一表通机构表", "内部机构号")),
                List.of(Map.of("file", "seed.json")), "database", "2026-10-21T00:00:00Z");

        assertEquals(List.of("east", "JGXXB"), NameDictionary.dictionaryKey("EAST.BD_ODS_JGXXB"));
        assertEquals(List.of("east", "JGXXB"), NameDictionary.dictionaryKey("EAST.T_EAST_YBT_JGXXB"));
        assertEquals(List.of("ybt", "YBT_T_10_20"), NameDictionary.dictionaryKey("YBT.YBT_T_10_20_V21"));
        assertTrue(NameDictionary.dictionaryKey("NO_DOT").isEmpty());
        assertTrue(NameDictionary.dictionaryKey("OTHER.TABLE").isEmpty());

        Map<String, Object> east = node("column:EAST.BD_ODS_JGXXB::NBJGH", "column",
                "EAST.BD_ODS_JGXXB::NBJGH", "NBJGH");
        Map<String, Object> metadata = dictionary.metadata(east);
        assertEquals("内部机构号", metadata.get("chinese_name"));
        assertEquals("机构信息表", metadata.get("table_chinese_name"));
        assertEquals("matched", metadata.get("name_status"));
        assertTrue(dictionary.matches(east, "内部"));
        assertEquals("内部机构号", dictionary.decorate(east).get("chinese_name"));

        Map<String, Object> conflict = new LinkedHashMap<>(east);
        conflict.put("original_names", List.of("YBT.YBT_T_10_20::NBJGH"));
        assertEquals("", dictionary.metadata(conflict).get("table_chinese_name"));
        assertEquals("matched", dictionary.metadata(conflict).get("name_status"));
        Map<String, Object> unmatched = node("column:EAST.BD_ODS_JGXXB::MISSING", "column",
                "EAST.BD_ODS_JGXXB::MISSING", "MISSING");
        assertEquals("unmatched", dictionary.metadata(unmatched).get("name_status"));
        assertEquals("not_applicable", dictionary.metadata(node("column:S.T::X", "column", "S.T::X", "X"))
                .get("name_status"));
        assertEquals("not_applicable", dictionary.metadata(node("constant:x", "constant", "CONST:x", "x"))
                .get("name_status"));
        assertEquals(2, dictionary.entries().size());
        assertEquals(2, dictionary.summary().get("fields"));
        assertTrue(NameDictionary.empty().entries().isEmpty());
    }

    @Test
    public void rejectsInvalidOrAmbiguousDictionaryRows() {
        Map<String, Object> valid = entry("east", "JGXXB", "NBJGH", "机构表", "机构号");
        assertThrows(IllegalArgumentException.class, () -> new NameDictionary(
                List.of(valid, valid), List.of(), "seed", null));
        assertThrows(IllegalArgumentException.class, () -> new NameDictionary(List.of(
                entry("east", "JGXXB", "NBJGH", "机构表", "机构号"),
                entry("east", "JGXXB", "NAME", "另一名称", "名称")), List.of(), "seed", null));
        assertThrows(IllegalArgumentException.class, () -> new NameDictionary(List.of(
                entry("ybt", "INVALID", "FIELD", "表", "字段")), List.of(), "seed", null));
    }

    private Map<String, Object> node(String id, String type, String qualified, String name) {
        return new LinkedHashMap<>(Map.of("id", id, "type", type, "qualified_name", qualified,
                "name", name, "namespace", qualified.contains(".") ? qualified.substring(0, qualified.indexOf('.')) : ""));
    }

    private Map<String, Object> entry(String sourceType, String table, String field,
                                      String tableCn, String fieldCn) {
        return new LinkedHashMap<>(Map.of("source_type", sourceType, "table_name_en", table,
                "field_name_en", field, "table_name_cn", tableCn, "field_name_cn", fieldCn,
                "source_file", "seed.json", "source_locations", List.of("row:1")));
    }
}
