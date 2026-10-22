package com.icbc.lineage.domain.catalog;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.icbc.lineage.common.DigestUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Independent Chinese display names. Entity IDs and graph edges never change. */
public final class NameDictionary {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Z][A-Z0-9_]*");
    private static final Pattern YBT_BASE = Pattern.compile("YBT_T_[0-9]+_[0-9]+");
    private static final Pattern YBT_V21 = Pattern.compile("(YBT_T_[0-9]+_[0-9]+)_V21");

    private final Map<String, Map<String, Object>> entries = new TreeMap<>();
    private final Map<String, Map<String, Object>> tableEntries = new TreeMap<>();
    private final List<Map<String, Object>> sources;
    private final String storageSource;
    private final String initializedAt;
    private final String configurationFingerprint;

    public NameDictionary(List<Map<String, Object>> entries, List<Map<String, Object>> sources,
                          String storageSource, String initializedAt) {
        this.sources = sources.stream().map(LinkedHashMap::new).<Map<String, Object>>map(Map::copyOf).toList();
        this.storageSource = storageSource == null ? "none" : storageSource;
        this.initializedAt = initializedAt;
        for (Map<String, Object> source : entries) {
            Map<String, Object> item = new LinkedHashMap<>(source);
            String sourceType = text(item.get("source_type")).toLowerCase(Locale.ROOT);
            String table = text(item.get("table_name_en")).toUpperCase(Locale.ROOT);
            String field = text(item.get("field_name_en")).toUpperCase(Locale.ROOT);
            String tableCn = text(item.get("table_name_cn"));
            String fieldCn = text(item.get("field_name_cn"));
            if ((!sourceType.equals("east") && !sourceType.equals("ybt")) || !IDENTIFIER.matcher(table).matches()
                    || sourceType.equals("ybt") && !YBT_BASE.matcher(table).matches()
                    || !IDENTIFIER.matcher(field).matches() || tableCn.isBlank() || fieldCn.isBlank()
                    || !tableCn.equals(tableCn.trim()) || !fieldCn.equals(fieldCn.trim())) {
                throw new IllegalArgumentException("Name dictionary contains an invalid identifier or name");
            }
            String key = key(sourceType, table, field);
            if (this.entries.containsKey(key)) {
                throw new IllegalArgumentException("Name dictionary contains a duplicate field: " + key);
            }
            String tableKey = key(sourceType, table, "");
            Map<String, Object> existing = tableEntries.get(tableKey);
            if (existing != null && !text(existing.get("table_name_cn")).equals(tableCn)) {
                throw new IllegalArgumentException("One table has conflicting Chinese names: " + table);
            }
            Object locations = item.get("source_locations");
            List<String> normalizedLocations = locations instanceof Collection<?> values
                    ? values.stream().map(NameDictionary::text).distinct().sorted().toList() : List.of();
            item.put("source_type", sourceType);
            item.put("table_name_en", table);
            item.put("field_name_en", field);
            item.put("source_locations", normalizedLocations);
            Map<String, Object> immutable = Map.copyOf(item);
            this.entries.put(key, immutable);
            tableEntries.putIfAbsent(tableKey, immutable);
        }
        this.configurationFingerprint = fingerprint();
    }

    public static NameDictionary empty() {
        return new NameDictionary(List.of(), List.of(), "none", null);
    }

    public static List<String> dictionaryKey(String table) {
        String normalized = text(table).trim().toUpperCase(Locale.ROOT);
        int separator = normalized.lastIndexOf('.');
        if (separator < 0) {
            return List.of();
        }
        String namespace = normalized.substring(0, separator);
        String name = normalized.substring(separator + 1);
        for (String prefix : List.of("T_EAST_YBT_", "BD_ODS_", "BD_ORG_")) {
            if (name.startsWith(prefix) && name.length() > prefix.length()) {
                return List.of("east", name.substring(prefix.length()));
            }
        }
        if (namespace.equals("YBT")) {
            Matcher versioned = YBT_V21.matcher(name);
            if (versioned.matches()) {
                return List.of("ybt", versioned.group(1));
            }
            if (YBT_BASE.matcher(name).matches()) {
                return List.of("ybt", name);
            }
        }
        return List.of();
    }

    public Map<String, Object> metadata(Map<String, Object> node) {
        List<String> references = new ArrayList<>();
        references.add(text(node.get("qualified_name")));
        Object names = node.get("original_names");
        if (names instanceof Collection<?> values) {
            values.stream().map(NameDictionary::text).filter(value -> !references.contains(value)).forEach(references::add);
        }
        if (text(node.get("type")).equals("constant")) {
            return result("chinese_name", "", "table_chinese_name", "", "name_status", "not_applicable",
                    "name_candidates", List.of(), "name_dictionary_basis", List.of());
        }
        Lookup field = lookup(references, text(node.get("type")).equals("column"));
        Lookup table = lookup(references, false);
        boolean applicable = references.stream().map(value -> value.split("::", 2)[0])
                .anyMatch(value -> !dictionaryKey(value).isEmpty());
        String status = !field.name.isEmpty() ? "matched" : !field.names.isEmpty() ? "conflict"
                : applicable ? "unmatched" : "not_applicable";
        return result("chinese_name", field.name, "table_chinese_name", table.name,
                "name_status", status, "name_candidates", field.names, "name_dictionary_basis", field.rows);
    }

    public Map<String, Object> decorate(Map<String, Object> node) {
        Map<String, Object> result = new LinkedHashMap<>(node);
        result.putAll(metadata(node));
        return result;
    }

    public boolean matches(Map<String, Object> node, String query) {
        String needle = text(query).toUpperCase(Locale.ROOT);
        @SuppressWarnings("unchecked")
        List<String> candidates = (List<String>) metadata(node).get("name_candidates");
        return candidates.stream().anyMatch(value -> value.toUpperCase(Locale.ROOT).contains(needle));
    }

    public Map<String, Object> summary() {
        Map<String, Long> counts = new TreeMap<>();
        entries.values().forEach(item -> counts.merge(text(item.get("source_type")), 1L, Long::sum));
        return result("storage_source", storageSource, "initialized_at", initializedAt,
                "configuration_fingerprint", configurationFingerprint, "sources", sources,
                "entries", entries.size(), "tables", tableEntries.size(), "fields", entries.size(),
                "source_type_counts", counts);
    }

    public List<Map<String, Object>> entries() {
        return List.copyOf(entries.values());
    }

    private Lookup lookup(List<String> references, boolean column) {
        Map<String, Map<String, Object>> found = new TreeMap<>();
        for (String reference : references) {
            String[] parts = reference.split("::", 2);
            List<String> dictionaryKey = dictionaryKey(parts[0]);
            if (dictionaryKey.isEmpty()) {
                continue;
            }
            String lookup = key(dictionaryKey.get(0), dictionaryKey.get(1),
                    column && parts.length > 1 ? parts[1].toUpperCase(Locale.ROOT) : "");
            Map<String, Object> item = column ? entries.get(lookup) : tableEntries.get(lookup);
            if (item != null) {
                found.put(lookup, item);
            }
        }
        String nameKey = column ? "field_name_cn" : "table_name_cn";
        List<String> names = found.values().stream().map(item -> text(item.get(nameKey))).distinct().sorted().toList();
        return new Lookup(names.size() == 1 ? names.get(0) : "", names, List.copyOf(found.values()));
    }

    private String fingerprint() {
        try {
            return DigestUtils.sha256(JSON.writeValueAsString(entries));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cannot fingerprint name dictionary", exception);
        }
    }

    private static String key(String sourceType, String table, String field) {
        return sourceType + "\0" + table + "\0" + field;
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static Map<String, Object> result(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            result.put(String.valueOf(values[index]), values[index + 1]);
        }
        return result;
    }

    private static final class Lookup {
        private final String name;
        private final List<String> names;
        private final List<Map<String, Object>> rows;

        private Lookup(String name, List<String> names, List<Map<String, Object>> rows) {
            this.name = name;
            this.names = names;
            this.rows = rows;
        }
    }
}
