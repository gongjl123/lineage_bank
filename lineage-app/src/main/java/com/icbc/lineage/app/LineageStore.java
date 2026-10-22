package com.icbc.lineage.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.icbc.lineage.api.ImportFile;
import com.icbc.lineage.api.DocumentSelection;
import com.icbc.lineage.api.storage.ImportBatch;
import com.icbc.lineage.api.storage.LineageRepository;
import com.icbc.lineage.api.storage.StoredDocument;
import com.icbc.lineage.common.DigestUtils;
import com.icbc.lineage.domain.catalog.NameDictionary;
import com.icbc.lineage.domain.catalog.TableNameMapper;
import com.icbc.lineage.domain.graph.LineageGraph;
import com.icbc.lineage.domain.graph.MappedLineageGraph;
import com.icbc.lineage.domain.graph.ValueLineageDecider;
import com.icbc.lineage.domain.model.LineageDocument;
import com.icbc.lineage.domain.model.LineageIssue;
import com.icbc.lineage.domain.model.NormalizedDocument;
import com.icbc.lineage.domain.model.BusinessLine;
import com.icbc.lineage.infrastructure.json.LineageDocumentAdapter;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Transactional document store and immutable online graph snapshot. */
@Service
public class LineageStore implements AutoCloseable {

    private final LineageRepository repository;
    private final LineageDocumentAdapter adapter;
    private final ValueLineageDecider valueLineageDecider;
    private final TableNameMapper mapper;
    private final NameDictionary dictionary;
    private volatile LineageSnapshot snapshot;

    public LineageStore(LineageRepository repository, LineageDocumentAdapter adapter,
                        ValueLineageDecider valueLineageDecider,
                        TableNameMapper fallbackMapper, NameDictionary fallbackDictionary) {
        this.repository = repository;
        this.adapter = adapter;
        this.valueLineageDecider = valueLineageDecider;
        this.mapper = repository.loadTableNameMapper(fallbackMapper);
        this.dictionary = repository.loadNameDictionary(fallbackDictionary);
        List<StoredDocument> loaded = repository.loadDocuments();
        Map<String, StoredDocument> restored = new LinkedHashMap<>();
        for (StoredDocument item : loaded) {
            restored.put(item.id(), item);
        }
        this.snapshot = build(restored);
    }

    public LineageSnapshot snapshot() {
        return snapshot;
    }

    public synchronized Map<String, Object> importFiles(List<ImportFile> files, boolean dryRun) {
        long started = System.nanoTime();
        Map<String, StoredDocument> proposed = new LinkedHashMap<>(snapshot.documents());
        Set<String> batchIds = new java.util.HashSet<>();
        int changed = 0;
        int unchanged = 0;
        for (ImportFile file : files) {
            NormalizedDocument normalized = adapter.parseAndNormalize(file.content(), file.filename(), file.line());
            String id = file.line().code() + ":" + normalized.document().datasetId();
            if (!batchIds.add(id)) {
                throw new IllegalArgumentException("Import batch contains a duplicate document: " + id);
            }
            String hash = DigestUtils.sha256(file.content());
            StoredDocument current = proposed.get(id);
            if (current != null && current.hash().equals(hash)) {
                unchanged++;
                continue;
            }
            LineageDocument graphDocument = graphDocument(id, normalized.document(), file.line().code());
            List<LineageIssue> issues = new ArrayList<>();
            for (LineageIssue issue : normalized.issues()) {
                issues.add(documentIssue(issue, id));
            }
            JsonNode original = normalized.original().deepCopy();
            proposed.put(id, new StoredDocument(id, file.line().code(), file.filename().replace('\\', '/'),
                    hash, original, graphDocument, issues, Instant.now()));
            changed++;
        }
        LineageSnapshot candidate = build(proposed);
        long elapsed = (System.nanoTime() - started) / 1_000_000L;
        ImportBatch batch = new ImportBatch(UUID.randomUUID().toString(), changed, unchanged,
                files.size(), elapsed, Instant.now());
        if (!dryRun && changed > 0) {
            repository.replaceAll(candidate.documents().values().stream().sorted(
                    Comparator.comparing(StoredDocument::id)).toList(), candidate.raw(), batch);
            snapshot = candidate;
        }
        return report(candidate, batch, dryRun);
    }

    public synchronized Map<String, Object> deleteDocuments(CollectionIds selection) {
        Map<String, StoredDocument> proposed = new LinkedHashMap<>(snapshot.documents());
        int changed = 0;
        for (String id : selection.ids()) {
            if (proposed.remove(id) != null) {
                changed++;
            }
        }
        if (changed == 0) {
            return result("changed", 0, "stats", snapshot.mapped().stats());
        }
        LineageSnapshot candidate = build(proposed);
        ImportBatch batch = new ImportBatch(UUID.randomUUID().toString(), changed, 0,
                selection.ids().size(), 0L, Instant.now());
        repository.replaceAll(candidate.documents().values().stream().sorted(
                Comparator.comparing(StoredDocument::id)).toList(), candidate.raw(), batch);
        snapshot = candidate;
        return result("changed", changed, "stats", candidate.mapped().stats());
    }

    public Map<String, Object> previewReassignment(List<DocumentSelection> selection, BusinessLine targetLine) {
        if (selection.isEmpty()) {
            throw new IllegalArgumentException("Select at least one document");
        }
        Set<String> selected = new java.util.HashSet<>();
        for (DocumentSelection item : selection) {
            if (!selected.add(item.datasetId())) {
                throw new IllegalArgumentException("Document selection contains duplicates");
            }
        }
        String target = targetLine.code();
        List<Map<String, Object>> items = new ArrayList<>();
        int changed = 0;
        int unchanged = 0;
        int conflicts = 0;
        int stale = 0;
        int missing = 0;
        for (DocumentSelection selectedItem : selection) {
            StoredDocument current = snapshot.documents().get(selectedItem.datasetId());
            String status;
            String destination = "";
            if (current == null) {
                status = "missing";
                missing++;
            } else if (!current.hash().equals(selectedItem.hash())) {
                status = "stale";
                stale++;
            } else if (current.line().equals(target)) {
                status = "unchanged";
                destination = current.id();
                unchanged++;
            } else {
                destination = target + ":" + nativeDatasetId(current);
                StoredDocument occupied = snapshot.documents().get(destination);
                if (occupied != null && !selected.contains(destination)) {
                    status = "conflict";
                    conflicts++;
                } else {
                    status = "changed";
                    changed++;
                }
            }
            items.add(result("dataset_id", selectedItem.datasetId(), "destination_id", destination,
                    "status", status, "hash", selectedItem.hash()));
        }
        String token = previewToken(selection, target);
        return result("target_line", target, "changed", changed, "unchanged", unchanged,
                "conflicts", conflicts, "stale", stale, "missing", missing,
                "can_apply", changed > 0 && conflicts == 0 && stale == 0 && missing == 0,
                "preview_token", token, "items", items);
    }

    public synchronized Map<String, Object> reassign(List<DocumentSelection> selection,
                                                      BusinessLine targetLine, String previewToken) {
        Map<String, Object> preview = previewReassignment(selection, targetLine);
        if (!text(preview.get("preview_token")).equals(previewToken)) {
            throw new IllegalArgumentException("Document state changed; preview the reassignment again");
        }
        if (!Boolean.TRUE.equals(preview.get("can_apply"))) {
            throw new IllegalArgumentException("Document reassignment contains conflicts or stale selections");
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) preview.get("items");
        Map<String, StoredDocument> proposed = new LinkedHashMap<>(snapshot.documents());
        for (Map<String, Object> item : items) {
            if (!"changed".equals(item.get("status"))) {
                continue;
            }
            String oldId = text(item.get("dataset_id"));
            String newId = text(item.get("destination_id"));
            StoredDocument current = proposed.remove(oldId);
            LineageDocument moved = graphDocument(newId, current.normalized(), targetLine.code());
            List<LineageIssue> movedIssues = new ArrayList<>();
            for (LineageIssue issue : current.issues()) {
                movedIssues.add(documentIssue(issue, newId));
            }
            proposed.put(newId, new StoredDocument(newId, targetLine.code(), current.filename(), current.hash(),
                    current.original(), moved, movedIssues, Instant.now()));
        }
        LineageSnapshot candidate = build(proposed);
        int changed = ((Number) preview.get("changed")).intValue();
        int unchanged = ((Number) preview.get("unchanged")).intValue();
        ImportBatch batch = new ImportBatch(UUID.randomUUID().toString(), changed, unchanged,
                selection.size(), 0L, Instant.now());
        repository.replaceAll(candidate.documents().values().stream().sorted(
                Comparator.comparing(StoredDocument::id)).toList(), candidate.raw(), batch);
        snapshot = candidate;
        return result("changed", changed, "unchanged", unchanged, "stats", candidate.mapped().stats());
    }

    public Map<String, Object> documents(String line, String query, int offset, int limit) {
        String needle = text(query).trim().toUpperCase(java.util.Locale.ROOT);
        List<Map<String, Object>> rows = snapshot.documents().values().stream()
                .filter(item -> line == null || line.isBlank() || item.line().equalsIgnoreCase(line))
                .filter(item -> needle.isBlank() || item.id().toUpperCase(java.util.Locale.ROOT).contains(needle)
                        || item.filename().toUpperCase(java.util.Locale.ROOT).contains(needle))
                .sorted(Comparator.comparing(StoredDocument::id))
                .map(this::manifest).toList();
        return result("total", rows.size(), "all_total", snapshot.documents().size(), "offset", offset,
                "items", rows.stream().skip(Math.max(0, offset)).limit(Math.max(0, limit)).toList());
    }

    public Map<String, Object> document(String id, boolean original) {
        StoredDocument item = snapshot.documents().get(id);
        if (item == null) {
            throw new IllegalArgumentException("Unknown document: " + id);
        }
        return original ? result("dataset_id", id, "content", item.original())
                : result("manifest", manifest(item), "content", item.normalized(), "issues", item.issues());
    }

    public String backend() {
        return repository.backend();
    }

    public int schemaVersion() {
        return repository.schemaVersion();
    }

    public TableNameMapper tableNameMapper() {
        return mapper;
    }

    public NameDictionary nameDictionary() {
        return dictionary;
    }

    @Override
    public void close() {
        repository.close();
    }

    private LineageSnapshot build(Map<String, StoredDocument> documents) {
        List<StoredDocument> ordered = documents.values().stream().sorted(Comparator.comparing(StoredDocument::id)).toList();
        List<LineageDocument> graphDocuments = ordered.stream().map(StoredDocument::normalized).toList();
        List<LineageIssue> issues = new ArrayList<>();
        for (StoredDocument item : ordered) {
            issues.addAll(item.issues());
        }
        LineageGraph raw = new LineageGraph(graphDocuments, issues, valueLineageDecider);
        MappedLineageGraph mapped = new MappedLineageGraph(raw, mapper, dictionary);
        Map<String, StoredDocument> immutable = new LinkedHashMap<>();
        for (StoredDocument item : ordered) {
            immutable.put(item.id(), item);
        }
        return new LineageSnapshot(raw, mapped, immutable);
    }

    private LineageDocument graphDocument(String id, LineageDocument source, String line) {
        Map<String, Object> metadata = new LinkedHashMap<>(source.metadata());
        metadata.put("business_line", line);
        metadata.put("original_dataset_id", source.datasetId());
        return new LineageDocument(source.schemaVersion(), id, source.scriptName(), source.derivations(), metadata);
    }

    private String nativeDatasetId(StoredDocument document) {
        Object original = document.normalized().metadata().get("original_dataset_id");
        if (original != null && !text(original).isBlank()) {
            return text(original);
        }
        int separator = document.id().indexOf(':');
        return separator < 0 ? document.id() : document.id().substring(separator + 1);
    }

    private String previewToken(List<DocumentSelection> selection, String targetLine) {
        List<String> values = new ArrayList<>();
        values.add(targetLine);
        selection.stream().sorted(Comparator.comparing(DocumentSelection::datasetId))
                .forEach(item -> values.add(item.datasetId() + "\0" + item.hash()));
        snapshot.documents().values().stream().sorted(Comparator.comparing(StoredDocument::id))
                .forEach(item -> values.add(item.id() + "\0" + item.hash()));
        return DigestUtils.sha256(String.join("\n", values));
    }

    private LineageIssue documentIssue(LineageIssue issue, String id) {
        return new LineageIssue(issue.code(), issue.message(), id, issue.location(), issue.severity(), issue.data());
    }

    private Map<String, Object> manifest(StoredDocument item) {
        return result("dataset_id", item.id(), "line", item.line(), "filename", item.filename(),
                "hash", item.hash(), "script_name", item.normalized().scriptName(),
                "derivations", item.normalized().derivations().size(), "issues", item.issues().size(),
                "updated_at", item.updatedAt().toString());
    }

    private Map<String, Object> report(LineageSnapshot candidate, ImportBatch batch, boolean dryRun) {
        return result("changed", batch.changed(), "unchanged", batch.unchanged(),
                "input_count", batch.inputCount(), "dry_run", dryRun, "elapsed_millis", batch.elapsedMillis(),
                "stats", candidate.mapped().stats(), "storage_schema_version", repository.schemaVersion());
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

    public record CollectionIds(List<String> ids) {
        public CollectionIds {
            ids = List.copyOf(ids);
        }
    }
}
