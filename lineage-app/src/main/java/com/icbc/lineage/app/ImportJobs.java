package com.icbc.lineage.app;

import com.icbc.lineage.api.ImportFile;
import com.icbc.lineage.api.DocumentSelection;
import com.icbc.lineage.domain.exception.DataValidationException;
import com.icbc.lineage.domain.model.BusinessLine;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** A single bounded worker for imports, matching the V6 no-external-queue deployment model. */
@Service
public class ImportJobs implements AutoCloseable {

    private static final int HISTORY_LIMIT = 20;
    private final LineageStore store;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "lineage-import");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<String, Map<String, Object>> jobs = new LinkedHashMap<>();

    public ImportJobs(LineageStore store) {
        this.store = store;
    }

    public synchronized String submit(List<ImportFile> files, boolean dryRun) {
        return submit(files, dryRun, ignored -> { });
    }

    public synchronized String submit(List<ImportFile> files, boolean dryRun, Consumer<String> onFinish) {
        String line = files.isEmpty() ? "" : files.get(0).line().code();
        String id = register(files.size(), line, dryRun, "import");
        List<ImportFile> immutable = List.copyOf(files);
        executor.submit(() -> run(id, immutable, dryRun, onFinish));
        return id;
    }

    public synchronized String submitReassignment(List<DocumentSelection> documents, BusinessLine line,
                                                   String previewToken) {
        String id = register(documents.size(), line.code(), false, "reassign");
        List<DocumentSelection> immutable = List.copyOf(documents);
        executor.submit(() -> runReassignment(id, immutable, line, previewToken));
        return id;
    }

    public synchronized Map<String, Object> get(String id) {
        Map<String, Object> value = jobs.get(id);
        if (value == null) {
            throw new JobNotFoundException("任务不存在或已过期，请刷新数据概览确认导入状态。");
        }
        return new LinkedHashMap<>(value);
    }

    private void run(String id, List<ImportFile> files, boolean dryRun, Consumer<String> onFinish) {
        update(id, "status", "running", "phase", "validating");
        String status = "failed";
        try {
            Map<String, Object> report = store.importFiles(files, dryRun);
            status = "succeeded";
            update(id, "status", status, "phase", "complete", "processed", files.size(),
                    "phase_total", files.size(), "phase_processed", files.size(), "result", report);
        } catch (DataValidationException exception) {
            update(id, "status", status, "phase", "failed", "error", "数据校验失败，本批未写入。",
                    "issues", exception.getIssues(), "failed_files", failedFiles(exception));
        } catch (RuntimeException exception) {
            update(id, "status", status, "phase", "failed", "error", exception.getMessage());
        } finally {
            onFinish.accept(status);
        }
    }

    private void runReassignment(String id, List<DocumentSelection> documents, BusinessLine line,
                                 String previewToken) {
        update(id, "status", "running", "phase", "validating", "phase_total", documents.size());
        try {
            Map<String, Object> report = store.reassign(documents, line, previewToken);
            update(id, "status", "succeeded", "phase", "complete", "processed", documents.size(),
                    "phase_processed", documents.size(), "result", report);
        } catch (RuntimeException exception) {
            update(id, "status", "failed", "phase", "failed", "error", exception.getMessage());
        }
    }

    private String register(int total, String line, boolean dryRun, String operation) {
        if (jobs.values().stream().anyMatch(this::active)) {
            throw new IllegalStateException("已有导入或归属更正任务正在处理，请等待完成。");
        }
        while (jobs.size() >= HISTORY_LIMIT) {
            jobs.remove(jobs.keySet().iterator().next());
        }
        String id = UUID.randomUUID().toString().replace("-", "");
        jobs.put(id, result("id", id, "status", "queued", "phase", "queued", "total", total,
                "processed", 0, "phase_total", total, "phase_processed", 0, "filename", "",
                "line", line, "dry_run", dryRun, "operation", operation));
        return id;
    }

    private List<String> failedFiles(DataValidationException exception) {
        List<String> result = new ArrayList<>();
        exception.getIssues().forEach(issue -> {
            if (!issue.document().isBlank() && !result.contains(issue.document())) {
                result.add(issue.document());
            }
        });
        return result;
    }

    private synchronized void update(String id, Object... values) {
        Map<String, Object> job = jobs.get(id);
        for (int index = 0; index < values.length; index += 2) {
            job.put(String.valueOf(values[index]), values[index + 1]);
        }
    }

    private boolean active(Map<String, Object> job) {
        return "queued".equals(job.get("status")) || "running".equals(job.get("status"));
    }

    @Override
    public void close() {
        executor.shutdown();
    }

    private static Map<String, Object> result(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            result.put(String.valueOf(values[index]), values[index + 1]);
        }
        return result;
    }

    public static class JobNotFoundException extends IllegalArgumentException {
        public JobNotFoundException(String message) {
            super(message);
        }
    }
}
