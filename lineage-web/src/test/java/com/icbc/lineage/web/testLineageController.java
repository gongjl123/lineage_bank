package com.icbc.lineage.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.icbc.lineage.api.DocumentSelection;
import com.icbc.lineage.app.ImportJobs;
import com.icbc.lineage.app.LineageStore;
import com.icbc.lineage.app.UploadException;
import com.icbc.lineage.app.UploadSessions;
import com.icbc.lineage.domain.catalog.NameDictionary;
import com.icbc.lineage.domain.catalog.TableNameMapper;
import com.icbc.lineage.domain.exception.DataValidationException;
import com.icbc.lineage.domain.model.LineageIssue;
import com.icbc.lineage.infrastructure.json.LineageDocumentAdapter;
import com.icbc.lineage.infrastructure.json.LineageJsonParser;
import com.icbc.lineage.infrastructure.json.LineageSchemaValidator;
import com.icbc.lineage.infrastructure.json.LineageSemanticValidator;
import com.icbc.lineage.infrastructure.sql.DruidValueLineageDecider;
import com.icbc.lineage.infrastructure.storage.JdbcLineageRepository;
import com.icbc.lineage.infrastructure.storage.StorageException;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.sqlite.SQLiteDataSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

public class testLineageController {

    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    private ObjectMapper json;
    private LineageStore store;
    private ImportJobs jobs;
    private UploadSessions uploads;
    private LineageController controller;
    private MockMvc mvc;

    @Before
    public void setUp() throws Exception {
        json = new ObjectMapper().findAndRegisterModules();
        Path database = temporary.newFile("web.sqlite3").toPath();
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + database.toAbsolutePath());
        JdbcLineageRepository repository = new JdbcLineageRepository(dataSource, "sqlite", "", json);
        LineageDocumentAdapter adapter = new LineageDocumentAdapter(new LineageJsonParser(),
                new LineageSchemaValidator(), new LineageSemanticValidator());
        store = new LineageStore(repository, adapter, new DruidValueLineageDecider(),
                TableNameMapper.empty(), NameDictionary.empty());
        jobs = new ImportJobs(store);
        uploads = new UploadSessions(temporary.newFolder("uploads").getAbsolutePath(), 1, 2, 3, 3600);
        controller = new LineageController(store, json, jobs, uploads);
        mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new ApiExceptionHandler()).build();
    }

    @After
    public void tearDown() {
        uploads.close();
        jobs.close();
        store.close();
    }

    @Test
    public void servesV6QueryAndDocumentContractsWithoutE2eRoutes() throws Exception {
        mvc.perform(post("/api/import?line=east").contentType(MediaType.APPLICATION_JSON)
                        .content(importBody("sample", document("sample"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.changed").value(1));
        mvc.perform(post("/api/import?line=east").contentType(MediaType.APPLICATION_JSON)
                        .content(importBody("issue", issueDocument())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.changed").value(1));

        ok("/api/health");
        ok("/api/lines");
        ok("/api/stats");
        ok("/api/stats?view=raw");
        ok("/api/table-mappings?q=S&offset=0&limit=10");
        ok("/api/mapping-status?q=S&offset=0&limit=10");
        ok("/api/nodes?kind=table&line=east");
        ok("/api/nodes?kind=column&parent_id=table:S.B&view=raw");
        ok("/api/node?id=column:S.B::X");
        ok("/api/node?id=column:S.B::X&view=raw");
        ok("/api/graph?id=column:S.B::X&direction=both&depth=3");
        ok("/api/trace?id=table:S.B&direction=upstream&depth=3&view=raw");
        ok("/api/impact?id=table:S.A");
        ok("/api/impact?id=table:S.A&view=raw");
        ok("/api/associated-tables?id=table:S.B");
        ok("/api/associated-tables?id=table:S.B&view=raw");
        ok("/api/table-path?id=table:S.A&target=table:S.B&direction=downstream");
        ok("/api/table-path?id=table:S.A&target=table:S.B&direction=downstream&view=raw");
        ok("/api/evidence?id=column:S.B::X&q=SELECT");
        ok("/api/evidence?id=column:S.B::X&view=raw");
        ok("/api/definitions?id=column:S.B::X");
        ok("/api/definitions?id=column:S.B::X&view=raw");
        ok("/api/issues");
        ok("/api/issues?view=raw");
        ok("/api/documents");
        ok("/api/document-files?line=east&q=sample");
        ok("/api/document?id=east:sample");
        ok("/api/document?id=east:sample&original=true");
        ok("/api/schema/lineage");
        ok("/api/schema/legacy");
        ok("/api/import-limits");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tags = (List<Map<String, Object>>) controller
                .businessTags("", 0, 20, "column:S.B::X", "mapped").get("items");
        assertFalse(tags.isEmpty());
        String tag = String.valueOf(tags.get(0).get("name"));
        mvc.perform(get("/api/business-tags").param("q", tag)).andExpect(status().isOk());
        mvc.perform(get("/api/business-upstream").param("id", "column:S.B::X").param("tag", tag))
                .andExpect(status().isOk());
        mvc.perform(get("/api/business-lineage").param("id", "column:S.B::X").param("tag", tag)
                        .param("direction", "both")).andExpect(status().isOk());
        mvc.perform(get("/api/business-branches").param("id", "column:S.B::X").param("tag", tag))
                .andExpect(status().isOk());
        ok("/api/business-label-evidence?id=column:S.B::X");
        mvc.perform(get("/api/nodes").param("kind", "column").param("business_tag", tag))
                .andExpect(status().isOk());
        mvc.perform(get("/api/graph").param("id", "column:S.B::X").param("business_tag", tag))
                .andExpect(status().isOk());
        mvc.perform(get("/api/table-path").param("id", "table:S.A").param("target", "table:S.B")
                        .param("direction", "downstream").param("business_tag", tag)).andExpect(status().isOk());

        mvc.perform(post("/api/validate?line=std").contentType(MediaType.APPLICATION_JSON)
                        .content(importBody("preview", document("preview"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.dry_run").value(true));
        MvcResult jobResult = mvc.perform(post("/api/import-jobs?line=ybt").contentType(MediaType.APPLICATION_JSON)
                        .content(importBody("job", document("job"))))
                .andExpect(status().isAccepted()).andReturn();
        String job = json.readTree(jobResult.getResponse().getContentAsString()).get("id").asText();
        await(job);
        mvc.perform(get("/api/import-jobs/{id}", job)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("succeeded"));

        String hash = store.snapshot().documents().get("east:sample").hash();
        List<DocumentSelection> selected = List.of(new DocumentSelection("east:sample", hash));
        Map<String, Object> preview = controller.previewReassignment(
                new LineageController.ReassignmentRequest(selected, "standard", ""));
        String reassignment = json.writeValueAsString(Map.of("documents", selected, "target_line", "standard",
                "preview_token", preview.get("preview_token")));
        mvc.perform(post("/api/document-files/reassign/preview").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("documents", selected, "target_line", "standard"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.can_apply").value(true));
        MvcResult reassigned = mvc.perform(post("/api/document-files/reassign")
                        .contentType(MediaType.APPLICATION_JSON).content(reassignment))
                .andExpect(status().isAccepted()).andReturn();
        String reassignmentJob = json.readTree(reassigned.getResponse().getContentAsString()).get("id").asText();
        await(reassignmentJob);
        assertEquals(1, ((Map<?, ?>) jobs.get(reassignmentJob).get("result")).get("changed"));

        mvc.perform(delete("/api/documents").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[\"standard:sample\"]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.changed").value(1));
        mvc.perform(post("/api/import-project")).andExpect(status().isGone());
        mvc.perform(get("/api/e2e/catalog")).andExpect(status().isNotFound());
        mvc.perform(get("/api/stats?view=invalid")).andExpect(status().isUnprocessableEntity());
        mvc.perform(get("/api/schema/unknown")).andExpect(status().isUnprocessableEntity());

        new LineageController.ImportRequest(null, null);
        new LineageController.ReassignmentRequest(null, "east", null);
        new LineageController.DeleteDocumentsRequest(null);
        new LineageController.UploadManifest(null);
    }

    @Test
    public void streamsUploadSessionIntoBackgroundImport() throws Exception {
        byte[] raw = document("uploaded").getBytes(StandardCharsets.UTF_8);
        String manifest = json.writeValueAsString(Map.of("documents", List.of(Map.of(
                "filename", "folder/uploaded.json", "size", raw.length, "last_modified", 1))));
        MvcResult created = mvc.perform(post("/api/import-sessions?line=std")
                        .contentType(MediaType.APPLICATION_JSON).content(manifest))
                .andExpect(status().isCreated()).andReturn();
        String session = json.readTree(created.getResponse().getContentAsString()).get("id").asText();
        mvc.perform(get("/api/import-sessions/{id}", session)).andExpect(status().isOk());
        mvc.perform(put("/api/import-sessions/{id}/files/0", session)
                        .contentType(MediaType.APPLICATION_OCTET_STREAM).content(raw))
                .andExpect(status().isOk()).andExpect(jsonPath("$.uploaded").value(true));
        MvcResult submitted = mvc.perform(post("/api/import-sessions/{id}/submit", session))
                .andExpect(status().isAccepted()).andReturn();
        String job = json.readTree(submitted.getResponse().getContentAsString()).get("id").asText();
        await(job);
        assertEquals(1, store.snapshot().documents().size());

        byte[] second = document("cancel").getBytes(StandardCharsets.UTF_8);
        String secondManifest = json.writeValueAsString(Map.of("documents", List.of(Map.of(
                "filename", "cancel.json", "size", second.length))));
        MvcResult cancel = mvc.perform(post("/api/import-sessions?line=east")
                        .contentType(MediaType.APPLICATION_JSON).content(secondManifest))
                .andExpect(status().isCreated()).andReturn();
        String cancelId = json.readTree(cancel.getResponse().getContentAsString()).get("id").asText();
        mvc.perform(delete("/api/import-sessions/{id}", cancelId)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("cancelled"));
    }

    @Test
    public void mapsApplicationExceptionsToStableHttpBodies() {
        ApiExceptionHandler handler = new ApiExceptionHandler();
        LineageIssue issue = LineageIssue.of("BAD", "invalid", "a", "root", LineageIssue.Severity.ERROR);
        assertEquals(422, handler.validation(new DataValidationException(List.of(issue))).getStatusCode().value());
        assertEquals(404, handler.jobNotFound(new ImportJobs.JobNotFoundException("missing")).getStatusCode().value());
        assertEquals(409, handler.upload(new UploadException("busy", UploadException.Status.CONFLICT))
                .getStatusCode().value());
        assertEquals(409, handler.conflict(new IllegalStateException("busy")).getStatusCode().value());
        assertEquals(422, handler.invalid(new IllegalArgumentException("bad")).getStatusCode().value());
        assertEquals(503, handler.storage(new StorageException("db")).getStatusCode().value());
    }

    private void ok(String path) throws Exception {
        MvcResult result = mvc.perform(get(path)).andReturn();
        assertEquals(result.getResponse().getContentAsString(), 200, result.getResponse().getStatus());
    }

    private void await(String id) throws Exception {
        for (int attempt = 0; attempt < 500; attempt++) {
            String status = String.valueOf(jobs.get(id).get("status"));
            if (status.equals("succeeded") || status.equals("failed")) {
                assertEquals("succeeded", status);
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("job did not finish");
    }

    private String importBody(String name, String content) throws Exception {
        return json.writeValueAsString(Map.of("documents", List.of(Map.of(
                "filename", name + ".json", "content", json.readTree(content)))));
    }

    private String document(String id) {
        return "{\"schema_version\":\"1.0\",\"dataset_id\":\"" + id
                + "\",\"script_name\":\"sample.sql\",\"derivations\":[{\"target\":\"S.B::X\","
                + "\"sources\":[{\"kind\":\"column\",\"ref\":\"S.A::X\",\"path\":\"p\"}],"
                + "\"transform_logic\":{\"p\":{\"expression\":\"A.X\",\"sql_fragment\":\"SELECT X FROM S.A\","
                + "\"YWGSBM\":\"'单位贷款' AS YWGSBM\"}}}]}";
    }

    private String issueDocument() {
        return "{\"schema_version\":\"1.0\",\"dataset_id\":\"issue\","
                + "\"script_name\":\"issue.sql\",\"derivations\":[{\"target\":\"S.ISSUE::X\","
                + "\"sources\":[],\"transform_logic\":{\"p\":{\"expression\":\"1\"}}}]}";
    }
}
