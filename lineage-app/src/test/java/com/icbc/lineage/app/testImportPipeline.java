package com.icbc.lineage.app;

import com.icbc.lineage.api.ImportFile;
import com.icbc.lineage.api.DocumentSelection;
import com.icbc.lineage.domain.catalog.NameDictionary;
import com.icbc.lineage.domain.catalog.TableNameMapper;
import com.icbc.lineage.domain.exception.DataValidationException;
import com.icbc.lineage.domain.model.BusinessLine;
import com.icbc.lineage.domain.model.LineageIssue;
import com.icbc.lineage.infrastructure.json.LineageDocumentAdapter;
import com.icbc.lineage.infrastructure.json.LineageJsonParser;
import com.icbc.lineage.infrastructure.json.LineageSchemaValidator;
import com.icbc.lineage.infrastructure.json.LineageSemanticValidator;
import com.icbc.lineage.infrastructure.sql.DruidValueLineageDecider;
import com.icbc.lineage.infrastructure.storage.JdbcLineageRepository;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.sqlite.SQLiteDataSource;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

public class testImportPipeline {

    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void backgroundJobsExposeProgressFailureAndSingleWorkerLimit() throws Exception {
        LineageStore mocked = mock(LineageStore.class);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            entered.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return Map.of("changed", 1);
        }).when(mocked).importFiles(any(), anyBoolean());
        ImportJobs jobs = new ImportJobs(mocked);
        List<ImportFile> files = List.of(new ImportFile(BusinessLine.EAST, "a.json", document("a")));
        String id = jobs.submit(files, false);
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        assertEquals("running", jobs.get(id).get("status"));
        assertThrows(IllegalStateException.class, () -> jobs.submit(files, false));
        assertThrows(ImportJobs.JobNotFoundException.class, () -> jobs.get("missing"));
        release.countDown();
        await(jobs, id);
        assertEquals("succeeded", jobs.get(id).get("status"));
        jobs.close();

        LineageStore failing = mock(LineageStore.class);
        LineageIssue issue = LineageIssue.of("INVALID_JSON", "bad", "bad.json", "root",
                LineageIssue.Severity.ERROR);
        doThrow(new DataValidationException(List.of(issue))).when(failing).importFiles(any(), anyBoolean());
        ImportJobs failedJobs = new ImportJobs(failing);
        String failedId = failedJobs.submit(files, true);
        await(failedJobs, failedId);
        assertEquals("failed", failedJobs.get(failedId).get("status"));
        assertEquals(List.of("bad.json"), failedJobs.get(failedId).get("failed_files"));
        failedJobs.close();
    }

    @Test
    public void diskBackedUploadCanValidateThenCommitWithoutDuplicateHistory() throws Exception {
        Path root = temporary.newFolder("uploads").toPath();
        LineageStore store = store(temporary.newFile("uploads.sqlite3").toPath());
        ImportJobs jobs = new ImportJobs(store);
        UploadSessions uploads = new UploadSessions(root.toString(), 1, 2, 3, 3600);
        byte[] raw = document("upload").getBytes(StandardCharsets.UTF_8);
        List<UploadSessions.UploadDeclaration> manifest = List.of(
                new UploadSessions.UploadDeclaration("folder/upload.json", raw.length, 1),
                new UploadSessions.UploadDeclaration("folder/.ipynb_checkpoints/ignored.json", 10, 0));
        Map<String, Object> created = uploads.create(manifest, BusinessLine.STANDARD);
        String session = String.valueOf(created.get("id"));
        assertEquals(1, created.get("total"));
        assertEquals(1, created.get("ignored"));
        assertFalse(uploads.limits().isEmpty());
        assertThrows(UploadException.class, () -> uploads.upload(session, 0,
                new ByteArrayInputStream(raw), raw.length - 1));
        assertThrows(UploadException.class, () -> uploads.upload(session, 0,
                new ByteArrayInputStream(java.util.Arrays.copyOf(raw, raw.length - 1)), -1));
        Map<String, Object> uploaded = uploads.upload(session, 0, new ByteArrayInputStream(raw), raw.length);
        assertEquals(true, uploaded.get("uploaded"));
        assertThrows(UploadException.class, () -> uploads.upload(session, 0,
                new ByteArrayInputStream(raw), raw.length));

        String previewJob = String.valueOf(uploads.submit(session, true, jobs).get("id"));
        await(jobs, previewJob);
        assertEquals("validated", uploads.get(session).get("status"));
        assertEquals(0, store.snapshot().documents().size());
        String commitJob = String.valueOf(uploads.submit(session, false, jobs).get("id"));
        await(jobs, commitJob);
        assertEquals(1, store.snapshot().documents().size());
        assertEquals("succeeded", uploads.get(session).get("status"));
        assertThrows(UploadException.class, () -> uploads.submit(session, false, jobs));

        String storedId = "standard:upload";
        String hash = store.snapshot().documents().get(storedId).hash();
        List<DocumentSelection> selection = List.of(new DocumentSelection(storedId, hash));
        Map<String, Object> preview = store.previewReassignment(selection, BusinessLine.YBT);
        String reassignment = jobs.submitReassignment(selection, BusinessLine.YBT,
                String.valueOf(preview.get("preview_token")));
        await(jobs, reassignment);
        assertEquals("reassign", jobs.get(reassignment).get("operation"));
        assertTrue(store.snapshot().documents().containsKey("ybt:upload"));

        Map<String, Object> other = uploads.create(List.of(
                new UploadSessions.UploadDeclaration("cancel.json", raw.length, 0)), BusinessLine.EAST);
        assertEquals("cancelled", uploads.cancel(String.valueOf(other.get("id"))).get("status"));
        assertThrows(UploadException.class, () -> uploads.get("missing"));
        uploads.close();
        jobs.close();
        store.close();
    }

    @Test
    public void uploadManifestRejectsUnsafeOrInvalidInputs() throws Exception {
        Path root = temporary.newFolder("invalid-uploads").toPath();
        assertThrows(IllegalArgumentException.class, () -> new UploadSessions(root.toString(), 0, 1, 1, 1));
        UploadSessions uploads = new UploadSessions(root.toString(), 1, 1, 1, 3600);
        assertThrows(UploadException.class, () -> uploads.create(List.of(), BusinessLine.EAST));
        assertThrows(UploadException.class, () -> uploads.create(List.of(
                new UploadSessions.UploadDeclaration("../bad.json", 1, 0)), BusinessLine.EAST));
        assertThrows(UploadException.class, () -> uploads.create(List.of(
                new UploadSessions.UploadDeclaration("large.json", 2 * 1024 * 1024L, 0)), BusinessLine.EAST));
        assertThrows(UploadException.class, () -> uploads.create(List.of(
                new UploadSessions.UploadDeclaration("same.json", 1, 0),
                new UploadSessions.UploadDeclaration("same.json", 1, 0)), BusinessLine.EAST));
        uploads.create(List.of(new UploadSessions.UploadDeclaration("first.json", 1, 0)), BusinessLine.EAST);
        uploads.create(List.of(new UploadSessions.UploadDeclaration("second.json", 1, 0)), BusinessLine.STD);
        assertEquals(422, new UploadException("invalid").status());
        uploads.close();

        UploadSessions expiring = new UploadSessions(root.toString(), 1, 1, 1, 1);
        String id = String.valueOf(expiring.create(List.of(
                new UploadSessions.UploadDeclaration("old.json", 1, 0)), BusinessLine.EAST).get("id"));
        Thread.sleep(1100);
        assertThrows(UploadException.class, () -> expiring.get(id));
        expiring.close();
    }

    private void await(ImportJobs jobs, String id) throws InterruptedException {
        for (int attempt = 0; attempt < 500; attempt++) {
            String status = String.valueOf(jobs.get(id).get("status"));
            if (status.equals("succeeded") || status.equals("failed")) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("job did not finish");
    }

    private LineageStore store(Path database) {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + database.toAbsolutePath());
        JdbcLineageRepository repository = new JdbcLineageRepository(dataSource, "sqlite", "");
        LineageDocumentAdapter adapter = new LineageDocumentAdapter(new LineageJsonParser(),
                new LineageSchemaValidator(), new LineageSemanticValidator());
        return new LineageStore(repository, adapter, new DruidValueLineageDecider(),
                TableNameMapper.empty(), NameDictionary.empty());
    }

    private String document(String id) {
        return "{\"schema_version\":\"1.0\",\"dataset_id\":\"" + id
                + "\",\"script_name\":\"sample.sql\",\"derivations\":[{\"target\":\"S.B::X\","
                + "\"sources\":[{\"kind\":\"column\",\"ref\":\"S.A::X\",\"path\":\"p\"}],"
                + "\"transform_logic\":{\"p\":{\"expression\":\"A.X\",\"sql_fragment\":\"SELECT X FROM S.A\"}}}]}";
    }
}
