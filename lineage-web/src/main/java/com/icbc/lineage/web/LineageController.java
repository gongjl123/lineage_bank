package com.icbc.lineage.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.icbc.lineage.api.DocumentSelection;
import com.icbc.lineage.api.ImportFile;
import com.icbc.lineage.app.ImportJobs;
import com.icbc.lineage.app.LineageStore;
import com.icbc.lineage.app.UploadSessions;
import com.icbc.lineage.domain.graph.BusinessIndex;
import com.icbc.lineage.domain.graph.LineageGraph;
import com.icbc.lineage.domain.graph.MappedLineageGraph;
import com.icbc.lineage.domain.model.BusinessLine;
import com.icbc.lineage.domain.model.LineageIssue;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api")
public class LineageController {

    private final LineageStore store;
    private final ObjectMapper json;
    private final ImportJobs jobs;
    private final UploadSessions uploads;

    public LineageController(LineageStore store, ObjectMapper json, ImportJobs jobs, UploadSessions uploads) {
        this.store = store;
        this.json = json;
        this.jobs = jobs;
        this.uploads = uploads;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return result("status", "ok", "version", "6.0.0", "storage", store.backend(),
                "storage_schema_version", store.schemaVersion(), "default_lineage_view", "mapped",
                "table_mapping", store.tableNameMapper().summary(),
                "name_dictionary", store.nameDictionary().summary(),
                "mapping_connectivity", store.snapshot().mapped().mappingSummary(),
                "features", result("end_to_end_report", false,
                        "business_label_lineage_inheritance", false));
    }

    @GetMapping("/lines")
    public Map<String, Object> lines(@RequestParam(defaultValue = "mapped") String view) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (BusinessLine line : BusinessLine.values()) {
            String code = line.code();
            long documents = store.snapshot().documents().values().stream()
                    .filter(item -> item.line().equals(code)).count();
            Map<String, Object> tables = nodes("", "table", "", "all", "", 0, 1, "", code, view);
            Map<String, Object> columns = nodes("", "column", "", "all", "", 0, 1, "", code, view);
            items.add(result("id", code, "name", code.toUpperCase(Locale.ROOT), "documents", documents,
                    "tables", tables.get("total"), "columns", columns.get("total")));
        }
        return result("items", items, "lineage_view", view);
    }

    @GetMapping("/stats")
    public Map<String, Object> stats(@RequestParam(defaultValue = "mapped") String view) {
        return mapped(view) ? store.snapshot().mapped().stats() : store.snapshot().raw().stats();
    }

    @GetMapping("/table-mappings")
    public Map<String, Object> tableMappings(@RequestParam(defaultValue = "") String q,
                                             @RequestParam(defaultValue = "0") int offset,
                                             @RequestParam(defaultValue = "100") int limit) {
        return store.tableNameMapper().catalog(q, offset, bounded(limit, 1, 500));
    }

    @GetMapping("/mapping-status")
    public Map<String, Object> mappingStatus(@RequestParam(defaultValue = "") String q,
                                             @RequestParam(defaultValue = "0") int offset,
                                             @RequestParam(defaultValue = "100") int limit) {
        return store.snapshot().mapped().mappingStatus(q, offset, bounded(limit, 1, 500), null);
    }

    @GetMapping("/business-tags")
    public Map<String, Object> businessTags(@RequestParam(defaultValue = "") String q,
                                            @RequestParam(defaultValue = "0") int offset,
                                            @RequestParam(defaultValue = "50") int limit,
                                            @RequestParam(defaultValue = "") String id,
                                            @RequestParam(defaultValue = "mapped") String view) {
        BusinessIndex business = business(view);
        String root = id.isBlank() ? "" : resolve(id, view);
        return business.catalog(q, offset, bounded(limit, 1, 200), root);
    }

    @GetMapping("/business-upstream")
    public Map<String, Object> businessUpstream(@RequestParam String id, @RequestParam String tag,
                                                @RequestParam(defaultValue = "1") int depth,
                                                @RequestParam(name = "max_nodes", defaultValue = "120") int maxNodes,
                                                @RequestParam(name = "include_constants", defaultValue = "false") boolean includeConstants,
                                                @RequestParam(name = "max_edges", defaultValue = "1000") int maxEdges,
                                                @RequestParam(defaultValue = "mapped") String view) {
        String root = resolve(id, view);
        Map<String, Object> result = business(view).fieldUpstream(root, tag, depth,
                bounded(maxNodes, 1, 1000), includeConstants, bounded(maxEdges, 1, 5000));
        return decorateBusiness(result, view);
    }

    @GetMapping("/business-lineage")
    public Map<String, Object> businessLineage(@RequestParam String id, @RequestParam String tag,
                                               @RequestParam(defaultValue = "both") String direction,
                                               @RequestParam(defaultValue = "1") int depth,
                                               @RequestParam(name = "max_nodes", defaultValue = "120") int maxNodes,
                                               @RequestParam(name = "include_constants", defaultValue = "false") boolean includeConstants,
                                               @RequestParam(name = "max_edges", defaultValue = "1000") int maxEdges,
                                               @RequestParam(defaultValue = "mapped") String view) {
        requireDirection(direction, true);
        String root = resolve(id, view);
        Map<String, Object> result = business(view).fieldLineage(root, tag, direction, depth,
                bounded(maxNodes, 1, 1000), includeConstants, bounded(maxEdges, 1, 5000));
        return decorateBusiness(result, view);
    }

    @GetMapping("/business-branches")
    public Map<String, Object> businessBranches(@RequestParam String id, @RequestParam String tag,
                                                @RequestParam(defaultValue = "0") int offset,
                                                @RequestParam(defaultValue = "20") int limit,
                                                @RequestParam(defaultValue = "mapped") String view) {
        return business(view).branchList(tag, resolve(id, view), offset, bounded(limit, 1, 100));
    }

    @GetMapping("/business-label-evidence")
    public Map<String, Object> businessEvidence(@RequestParam String id,
                                                @RequestParam(defaultValue = "0") int offset,
                                                @RequestParam(defaultValue = "20") int limit,
                                                @RequestParam(defaultValue = "mapped") String view) {
        return business(view).diagnostics(resolve(id, view), offset, bounded(limit, 1, 100));
    }

    @GetMapping("/nodes")
    public Map<String, Object> nodes(@RequestParam(defaultValue = "") String q,
                                     @RequestParam(defaultValue = "table") String kind,
                                     @RequestParam(defaultValue = "") String namespace,
                                     @RequestParam(defaultValue = "all") String status,
                                     @RequestParam(name = "parent_id", defaultValue = "") String parentId,
                                     @RequestParam(defaultValue = "0") int offset,
                                     @RequestParam(defaultValue = "50") int limit,
                                     @RequestParam(name = "business_tag", defaultValue = "") String businessTag,
                                     @RequestParam(defaultValue = "") String line,
                                     @RequestParam(defaultValue = "mapped") String view) {
        Set<String> visible = businessTag.isBlank() ? null : business(view).matchingNodeIds(businessTag);
        if (!businessTag.isBlank() && visible.isEmpty()) {
            throw new IllegalArgumentException("Unknown business tag: " + businessTag);
        }
        if (mapped(view)) {
            return store.snapshot().mapped().search(q, kind, namespace, status, parentId, offset,
                    bounded(limit, 1, 200), line, visible);
        }
        return store.snapshot().raw().search(q, kind, namespace, status, parentId, offset,
                bounded(limit, 1, 200), line, visible);
    }

    @GetMapping("/node")
    public Map<String, Object> node(@RequestParam String id,
                                    @RequestParam(defaultValue = "mapped") String view) {
        Map<String, Object> value = mapped(view) ? store.snapshot().mapped().node(id) : store.snapshot().raw().node(id);
        value.put("lineage_view", view);
        return value;
    }

    @GetMapping({"/graph", "/trace"})
    public Map<String, Object> graph(@RequestParam String id,
                                     @RequestParam(defaultValue = "both") String direction,
                                     @RequestParam(defaultValue = "1") int depth,
                                     @RequestParam(name = "max_nodes", defaultValue = "120") int maxNodes,
                                     @RequestParam(name = "include_constants", defaultValue = "false") boolean includeConstants,
                                     @RequestParam(name = "max_edges", defaultValue = "1000") int maxEdges,
                                     @RequestParam(name = "business_tag", defaultValue = "") String businessTag,
                                     @RequestParam(defaultValue = "mapped") String view) {
        requireDirection(direction, true);
        LineageGraph graph = graph(view);
        String root = resolve(id, view);
        boolean valueOnly = "column".equals(graph.node(root).get("type"));
        Map<String, Object> result = graph.subgraph(root, direction, depth, bounded(maxNodes, 1, 1000),
                includeConstants, bounded(maxEdges, 1, 5000),
                valueOnly ? (source, target, traversal) -> graph.valueEdge(source, target) : null);
        if (!businessTag.isBlank()) {
            if (business(view).matchingNodeIds(businessTag).isEmpty()) {
                throw new IllegalArgumentException("Unknown business tag: " + businessTag);
            }
            result = business(view).annotate(result, businessTag);
        }
        result.put("value_lineage", valueOnly);
        return mapped(view) ? store.snapshot().mapped().decorateResult(result) : result;
    }

    @GetMapping("/impact")
    public Map<String, Object> impact(@RequestParam String id,
                                      @RequestParam(defaultValue = "100") int depth,
                                      @RequestParam(name = "max_nodes", defaultValue = "20000") int maxNodes,
                                      @RequestParam(defaultValue = "mapped") String view) {
        return mapped(view) ? store.snapshot().mapped().impact(id, depth, bounded(maxNodes, 1, 50000))
                : store.snapshot().raw().impact(id, depth, bounded(maxNodes, 1, 50000));
    }

    @GetMapping("/associated-tables")
    public Map<String, Object> associatedTables(@RequestParam String id,
                                                @RequestParam(name = "max_nodes", defaultValue = "20000") int maxNodes,
                                                @RequestParam(defaultValue = "mapped") String view) {
        return mapped(view) ? store.snapshot().mapped().associatedTables(id, bounded(maxNodes, 1, 50000))
                : store.snapshot().raw().associatedTables(id, bounded(maxNodes, 1, 50000));
    }

    @GetMapping("/table-path")
    public Map<String, Object> tablePath(@RequestParam String id, @RequestParam String target,
                                         @RequestParam String direction,
                                         @RequestParam(name = "max_nodes", defaultValue = "20000") int maxNodes,
                                         @RequestParam(name = "business_tag", defaultValue = "") String businessTag,
                                         @RequestParam(defaultValue = "mapped") String view) {
        requireDirection(direction, false);
        Map<String, Object> result = mapped(view)
                ? store.snapshot().mapped().tablePath(id, target, direction, bounded(maxNodes, 1, 50000))
                : store.snapshot().raw().tablePath(id, target, direction, bounded(maxNodes, 1, 50000));
        return businessTag.isBlank() ? result : business(view).annotate(result, businessTag);
    }

    @GetMapping("/evidence")
    public Map<String, Object> evidence(@RequestParam String id,
                                        @RequestParam(defaultValue = "") String source,
                                        @RequestParam(defaultValue = "") String target,
                                        @RequestParam(defaultValue = "0") int offset,
                                        @RequestParam(defaultValue = "30") int limit,
                                        @RequestParam(defaultValue = "") String q,
                                        @RequestParam(name = "business_tag", defaultValue = "") String businessTag,
                                        @RequestParam(name = "business_root", defaultValue = "") String businessRoot,
                                        @RequestParam(defaultValue = "mapped") String view) {
        int pageSize = bounded(limit, 1, 100);
        Set<Integer> allowed = null;
        if (!businessTag.isBlank()) {
            String root = businessRoot.isBlank() ? resolve(id, view) : resolve(businessRoot, view);
            allowed = business(view).relationIndexes(businessTag, root);
        }
        return mapped(view) ? store.snapshot().mapped().evidence(id, source, target, offset,
                pageSize, q, allowed) : store.snapshot().raw().evidence(id, source, target, offset,
                pageSize, q, allowed);
    }

    @GetMapping("/definitions")
    public Map<String, Object> definitions(@RequestParam String id,
                                           @RequestParam(defaultValue = "0") int offset,
                                           @RequestParam(defaultValue = "20") int limit,
                                           @RequestParam(defaultValue = "") String q,
                                           @RequestParam(defaultValue = "mapped") String view) {
        return mapped(view) ? store.snapshot().mapped().definitions(id, offset, bounded(limit, 1, 100), q)
                : store.snapshot().raw().definitions(id, offset, bounded(limit, 1, 100), q);
    }

    @GetMapping("/issues")
    public Map<String, Object> issues(@RequestParam(defaultValue = "") String code,
                                      @RequestParam(defaultValue = "") String severity,
                                      @RequestParam(defaultValue = "") String q,
                                      @RequestParam(defaultValue = "0") int offset,
                                      @RequestParam(defaultValue = "50") int limit,
                                      @RequestParam(defaultValue = "mapped") String view) {
        String needle = q.trim().toUpperCase(Locale.ROOT);
        List<LineageIssue> source = mapped(view) ? store.snapshot().mapped().issues() : store.snapshot().raw().issues();
        List<Map<String, Object>> rows = source.stream()
                .filter(item -> code.isBlank() || item.code().equalsIgnoreCase(code))
                .filter(item -> severity.isBlank() || item.severity().name().equalsIgnoreCase(severity))
                .map(this::issue)
                .filter(item -> needle.isBlank() || item.toString().toUpperCase(Locale.ROOT).contains(needle))
                .sorted(Comparator.comparing(item -> String.valueOf(item.get("code"))))
                .toList();
        return result("total", rows.size(), "offset", offset,
                "items", rows.stream().skip(Math.max(0, offset)).limit(bounded(limit, 1, 200)).toList());
    }

    @GetMapping("/documents")
    public Map<String, Object> documents() {
        return store.documents("", "", 0, Integer.MAX_VALUE);
    }

    @GetMapping("/document")
    public Map<String, Object> document(@RequestParam String id,
                                        @RequestParam(defaultValue = "false") boolean original) {
        return store.document(id, original);
    }

    @GetMapping("/document-files")
    public Map<String, Object> documentFiles(@RequestParam(defaultValue = "") String line,
                                             @RequestParam(defaultValue = "") String q,
                                             @RequestParam(defaultValue = "0") int offset,
                                             @RequestParam(defaultValue = "50") int limit) {
        return store.documents(line, q, offset, bounded(limit, 1, 200));
    }

    @PostMapping("/document-files/reassign/preview")
    public Map<String, Object> previewReassignment(@RequestBody ReassignmentRequest request) {
        return store.previewReassignment(request.documents(), BusinessLine.from(request.targetLine()));
    }

    @PostMapping("/document-files/reassign")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, Object> reassign(@RequestBody ReassignmentRequest request) {
        return result("id", jobs.submitReassignment(request.documents(), BusinessLine.from(request.targetLine()),
                request.previewToken()));
    }

    @DeleteMapping("/documents")
    public Map<String, Object> deleteDocuments(@RequestBody DeleteDocumentsRequest request) {
        return store.deleteDocuments(new LineageStore.CollectionIds(request.ids()));
    }

    @PostMapping("/import")
    public Map<String, Object> importDocuments(@RequestBody ImportRequest request,
                                               @RequestParam(defaultValue = "east") String line) {
        return store.importFiles(importFiles(request, line), false);
    }

    @PostMapping("/validate")
    public Map<String, Object> validateDocuments(@RequestBody ImportRequest request,
                                                 @RequestParam(defaultValue = "east") String line) {
        return store.importFiles(importFiles(request, line), true);
    }

    @PostMapping("/import-jobs")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, Object> importJob(@RequestBody ImportRequest request,
                                         @RequestParam String line,
                                         @RequestParam(name = "dry_run", defaultValue = "false") boolean dryRun) {
        return result("id", jobs.submit(importFiles(request, line), dryRun));
    }

    @GetMapping("/import-jobs/{id}")
    public Map<String, Object> importJobStatus(@PathVariable String id) {
        return jobs.get(id);
    }

    @GetMapping("/import-limits")
    public Map<String, Object> importLimits() {
        return uploads.limits();
    }

    @PostMapping("/import-sessions")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createUpload(@RequestBody UploadManifest manifest, @RequestParam String line) {
        List<UploadSessions.UploadDeclaration> declarations = manifest.documents().stream()
                .map(item -> new UploadSessions.UploadDeclaration(item.filename(), item.size(), item.lastModified()))
                .toList();
        return uploads.create(declarations, BusinessLine.from(line));
    }

    @GetMapping("/import-sessions/{id}")
    public Map<String, Object> uploadStatus(@PathVariable String id) {
        return uploads.get(id);
    }

    @org.springframework.web.bind.annotation.PutMapping("/import-sessions/{id}/files/{index}")
    public Map<String, Object> uploadFile(@PathVariable String id, @PathVariable int index,
                                          HttpServletRequest request) throws IOException {
        return uploads.upload(id, index, request.getInputStream(), request.getContentLengthLong());
    }

    @PostMapping("/import-sessions/{id}/submit")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, Object> submitUpload(@PathVariable String id,
                                            @RequestParam(name = "dry_run", defaultValue = "false") boolean dryRun) {
        return uploads.submit(id, dryRun, jobs);
    }

    @DeleteMapping("/import-sessions/{id}")
    public Map<String, Object> cancelUpload(@PathVariable String id) {
        return uploads.cancel(id);
    }

    @GetMapping("/schema/{name}")
    public JsonNode schema(@PathVariable String name) throws IOException {
        if (!Set.of("lineage", "legacy").contains(name)) {
            throw new IllegalArgumentException("Unknown schema: " + name);
        }
        ClassPathResource resource = new ClassPathResource("schemas/" + name + ".schema.json");
        return json.readTree(resource.getInputStream());
    }

    @PostMapping("/import-project")
    @ResponseStatus(HttpStatus.GONE)
    public Map<String, Object> importProject() {
        return result("detail", "Select JSON files or use the import API; server-side directory import is disabled");
    }

    private List<ImportFile> importFiles(ImportRequest request, String queryLine) {
        String selectedLine = request.line() == null || request.line().isBlank() ? queryLine : request.line();
        BusinessLine fallback = BusinessLine.from(selectedLine);
        if (request.documents().isEmpty()) {
            throw new IllegalArgumentException("每批需要至少 1 份文档。");
        }
        return request.documents().stream().map(item -> new ImportFile(
                item.line() == null || item.line().isBlank() ? fallback : BusinessLine.from(item.line()),
                item.filename(), item.content().isTextual() ? item.content().asText() : item.content().toString())).toList();
    }

    private LineageGraph graph(String view) {
        return mapped(view) ? store.snapshot().mapped().projectedGraph() : store.snapshot().raw();
    }

    private BusinessIndex business(String view) {
        return mapped(view) ? store.snapshot().mapped().business() : store.snapshot().raw().business();
    }

    private String resolve(String id, String view) {
        return mapped(view) ? store.snapshot().mapped().resolve(id) : store.snapshot().raw().resolve(id);
    }

    private boolean mapped(String view) {
        if (!Set.of("mapped", "raw").contains(view)) {
            throw new IllegalArgumentException("lineage view must be mapped or raw");
        }
        return view.equals("mapped");
    }

    private Map<String, Object> decorateBusiness(Map<String, Object> result, String view) {
        return mapped(view) ? store.snapshot().mapped().decorateResult(result) : result;
    }

    private void requireDirection(String direction, boolean allowBoth) {
        if (!Set.of("upstream", "downstream").contains(direction) && !(allowBoth && direction.equals("both"))) {
            throw new IllegalArgumentException("Invalid lineage direction: " + direction);
        }
    }

    private int bounded(int value, int minimum, int maximum) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException("Value must be between " + minimum + " and " + maximum);
        }
        return value;
    }

    private Map<String, Object> issue(LineageIssue issue) {
        return result("code", issue.code(), "message", issue.message(), "document", issue.document(),
                "location", issue.location(), "severity", issue.severity().name().toLowerCase(Locale.ROOT),
                "data", issue.data());
    }

    private static Map<String, Object> result(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            result.put(String.valueOf(values[index]), values[index + 1]);
        }
        return result;
    }

    public record ImportDocument(String filename, JsonNode content, String line) { }
    public record ImportRequest(List<ImportDocument> documents, String line) {
        public ImportRequest {
            documents = documents == null ? List.of() : List.copyOf(documents);
        }
    }
    public record ReassignmentRequest(List<DocumentSelection> documents,
                                      @com.fasterxml.jackson.annotation.JsonProperty("target_line") String targetLine,
                                      @com.fasterxml.jackson.annotation.JsonProperty("preview_token") String previewToken) {
        public ReassignmentRequest {
            documents = documents == null ? List.of() : List.copyOf(documents);
        }
    }
    public record DeleteDocumentsRequest(List<String> ids) {
        public DeleteDocumentsRequest {
            ids = ids == null ? List.of() : List.copyOf(ids);
        }
    }
    public record UploadDocument(String filename, long size,
                                 @com.fasterxml.jackson.annotation.JsonProperty("last_modified") long lastModified) { }
    public record UploadManifest(List<UploadDocument> documents) {
        public UploadManifest {
            documents = documents == null ? List.of() : List.copyOf(documents);
        }
    }
}
