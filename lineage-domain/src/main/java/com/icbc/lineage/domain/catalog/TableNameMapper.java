package com.icbc.lineage.domain.catalog;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.icbc.lineage.common.DigestUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Table A to table B mapping used by the business lineage view. */
public final class TableNameMapper {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Map<String, String> mappings;
    private final Map<String, List<String>> sheets;
    private final Map<String, List<String>> reverse;
    private final String workbook;
    private final String fingerprint;
    private final String storageSource;
    private final String initializedAt;
    private final String configurationFingerprint;

    public TableNameMapper(Map<String, String> mappings, Map<String, ? extends Collection<String>> sheets,
                           String workbook, String fingerprint, String storageSource, String initializedAt) {
        Map<String, String> normalized = new TreeMap<>();
        mappings.forEach((source, target) -> normalized.put(normalize(source), normalize(target)));
        normalized.forEach((source, target) -> {
            String next = normalized.getOrDefault(target, target);
            if (!source.equals(target) && !next.equals(target)) {
                throw new IllegalArgumentException("Table mapping cannot contain a chain or cycle: "
                        + source + " -> " + target + " -> " + next);
            }
        });
        this.mappings = Map.copyOf(normalized);
        Map<String, List<String>> normalizedSheets = new TreeMap<>();
        sheets.forEach((source, values) -> normalizedSheets.put(normalize(source),
                values.stream().filter(value -> value != null && !value.isBlank()).distinct().sorted().toList()));
        this.sheets = Map.copyOf(normalizedSheets);
        this.workbook = workbook == null ? "" : workbook;
        this.fingerprint = fingerprint == null ? "" : fingerprint;
        this.storageSource = storageSource == null ? "memory" : storageSource;
        this.initializedAt = initializedAt;
        Map<String, List<String>> reverseIndex = new TreeMap<>();
        this.mappings.forEach((source, target) -> reverseIndex.computeIfAbsent(target, key -> new ArrayList<>()).add(source));
        reverseIndex.replaceAll((key, value) -> value.stream().sorted().toList());
        this.reverse = Map.copyOf(reverseIndex);
        this.configurationFingerprint = configurationFingerprint();
    }

    public static TableNameMapper empty() {
        return new TableNameMapper(Map.of(), Map.of(), "", "", "none", null);
    }

    public String mappedTable(String table) {
        String normalized = normalize(table);
        return mappings.getOrDefault(normalized, normalized);
    }

    public Map<String, Object> decorate(Map<String, Object> node) {
        Map<String, Object> result = new LinkedHashMap<>(node);
        String original = text(node.get("qualified_name"));
        String table = tableFromNode(node);
        if (table.isEmpty()) {
            result.put("display_name", text(node.get("name")));
            result.put("display_qualified_name", original);
            result.put("display_namespace", text(node.get("namespace")));
            result.put("display_table", "");
            result.put("original_qualified_name", original);
            result.put("original_table", "");
            result.put("mapping_configured", false);
            result.put("mapping_changed", false);
            result.put("mapping_sheets", List.of());
            result.put("mapped_source_count", 0);
            return result;
        }
        String mapped = mappedTable(table);
        String suffix = original.substring(table.length());
        int separator = mapped.lastIndexOf('.');
        boolean configured = mappings.containsKey(table);
        result.put("display_name", text(node.get("type")).equals("table")
                ? mapped.substring(separator + 1) : text(node.get("name")));
        result.put("display_qualified_name", mapped + suffix);
        result.put("display_namespace", separator < 0 ? "" : mapped.substring(0, separator));
        result.put("display_table", mapped);
        result.put("original_qualified_name", original);
        result.put("original_table", table);
        result.put("mapping_configured", configured);
        result.put("mapping_changed", !mapped.equals(table));
        result.put("mapping_sheets", sheets.getOrDefault(table, List.of()));
        result.put("mapped_source_count", configured ? reverse.getOrDefault(mapped, List.of()).size() : 0);
        return result;
    }

    public String displayEntityId(String entityId) {
        int prefix = entityId.indexOf(':');
        if (prefix < 0) {
            return entityId;
        }
        String kind = entityId.substring(0, prefix);
        String value = entityId.substring(prefix + 1);
        if (!kind.equals("table") && !kind.equals("column")) {
            return value;
        }
        int field = value.indexOf("::");
        String table = field < 0 ? value : value.substring(0, field);
        return mappedTable(table) + (field < 0 ? "" : value.substring(field));
    }

    public boolean matches(Map<String, Object> node, String query) {
        String needle = text(query).toUpperCase(Locale.ROOT);
        if (needle.isEmpty()) {
            return true;
        }
        Map<String, Object> decorated = decorate(node);
        if (text(node.get("qualified_name")).toUpperCase(Locale.ROOT).contains(needle)
                || text(decorated.get("display_qualified_name")).toUpperCase(Locale.ROOT).contains(needle)) {
            return true;
        }
        Object aliases = node.get("original_names");
        return aliases instanceof Collection<?> names
                && names.stream().map(this::text).anyMatch(name -> name.toUpperCase(Locale.ROOT).contains(needle));
    }

    public Map<String, Object> summary() {
        long changed = mappings.entrySet().stream().filter(item -> !item.getKey().equals(item.getValue())).count();
        long duplicateTargets = reverse.values().stream().filter(values -> values.size() > 1).count();
        int duplicateSources = reverse.values().stream().filter(values -> values.size() > 1).mapToInt(List::size).sum();
        Map<String, Long> sheetCounts = new TreeMap<>();
        sheets.values().forEach(values -> values.forEach(sheet -> sheetCounts.merge(sheet, 1L, Long::sum)));
        return result("storage_source", storageSource, "initialized_at", initializedAt,
                "workbook", workbook, "fingerprint", fingerprint,
                "configuration_fingerprint", configurationFingerprint,
                "sheet_unique_source_counts", sheetCounts, "unique_sources", mappings.size(),
                "changed_sources", changed, "identity_sources", mappings.size() - changed,
                "duplicate_targets", duplicateTargets, "sources_in_duplicate_targets", duplicateSources);
    }

    public Map<String, Object> catalog(String query, int offset, int limit) {
        String needle = text(query).trim().toUpperCase(Locale.ROOT);
        List<Map<String, Object>> rows = mappings.entrySet().stream()
                .filter(item -> needle.isEmpty() || item.getKey().contains(needle) || item.getValue().contains(needle))
                .map(item -> result("source", item.getKey(), "target", item.getValue(),
                        "changed", !item.getKey().equals(item.getValue()),
                        "sheets", sheets.getOrDefault(item.getKey(), List.of()),
                        "mapped_source_count", reverse.get(item.getValue()).size()))
                .sorted(Comparator.comparing(item -> text(item.get("target")) + "\0" + text(item.get("source"))))
                .toList();
        return result("total", rows.size(), "offset", offset,
                "items", rows.stream().skip(Math.max(0, offset)).limit(Math.max(0, limit)).toList(),
                "summary", summary());
    }

    public Map<String, String> mappings() {
        return mappings;
    }

    public List<String> sheets(String source) {
        return sheets.getOrDefault(normalize(source), List.of());
    }

    private String configurationFingerprint() {
        try {
            List<Object> rows = mappings.entrySet().stream().map(item -> (Object) List.of(
                    item.getKey(), item.getValue(), sheets.getOrDefault(item.getKey(), List.of()))).toList();
            return DigestUtils.sha256(JSON.writeValueAsString(rows));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cannot fingerprint table mappings", exception);
        }
    }

    private String tableFromNode(Map<String, Object> node) {
        String type = text(node.get("type"));
        String qualified = text(node.get("qualified_name"));
        if (type.equals("table")) {
            return qualified;
        }
        if (type.equals("column")) {
            int field = qualified.indexOf("::");
            return field < 0 ? qualified : qualified.substring(0, field);
        }
        return "";
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static Map<String, Object> result(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            result.put(String.valueOf(values[index]), values[index + 1]);
        }
        return result;
    }
}
