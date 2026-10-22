package com.icbc.lineage.app;

import com.icbc.lineage.api.ImportFile;
import com.icbc.lineage.domain.model.BusinessLine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.icbc.lineage.app.UploadException.Status.CONFLICT;
import static com.icbc.lineage.app.UploadException.Status.INTERNAL_SERVER_ERROR;
import static com.icbc.lineage.app.UploadException.Status.NOT_FOUND;
import static com.icbc.lineage.app.UploadException.Status.PAYLOAD_TOO_LARGE;

/** Bounded disk-backed upload sessions. Staged files never represent committed business state. */
@Service
public class UploadSessions implements AutoCloseable {

    private static final int MAX_FILES = 5000;
    private static final int MAX_SESSIONS = 4;
    private final Path workspace;
    private final long maxFileBytes;
    private final long maxBatchBytes;
    private final long maxStagedBytes;
    private final long expiresSeconds;
    private final Map<String, Session> sessions = new LinkedHashMap<>();

    public UploadSessions(@Value("${lineage.upload.directory:./var/uploads}") String directory,
                          @Value("${lineage.upload.max-file-mb:64}") long maxFileMb,
                          @Value("${lineage.upload.max-batch-mb:512}") long maxBatchMb,
                          @Value("${lineage.upload.max-staged-mb:1024}") long maxStagedMb,
                          @Value("${lineage.upload.ttl-seconds:3600}") long expiresSeconds) {
        this.maxFileBytes = positive(maxFileMb, "max-file-mb") * 1024L * 1024L;
        this.maxBatchBytes = positive(maxBatchMb, "max-batch-mb") * 1024L * 1024L;
        this.maxStagedBytes = positive(maxStagedMb, "max-staged-mb") * 1024L * 1024L;
        this.expiresSeconds = positive(expiresSeconds, "ttl-seconds");
        try {
            Path root = Path.of(directory).toAbsolutePath().normalize();
            Files.createDirectories(root);
            this.workspace = Files.createTempDirectory(root, "worker-").toAbsolutePath().normalize();
        } catch (IOException exception) {
            throw new UploadException("无法创建上传临时目录。", INTERNAL_SERVER_ERROR);
        }
    }

    public synchronized Map<String, Object> limits() {
        return result("max_file_bytes", maxFileBytes, "max_batch_bytes", maxBatchBytes,
                "max_staged_bytes", maxStagedBytes, "max_files", MAX_FILES,
                "max_sessions", MAX_SESSIONS, "expires_seconds", expiresSeconds);
    }

    public synchronized Map<String, Object> create(List<UploadDeclaration> declarations, BusinessLine line) {
        expire();
        if (declarations == null || declarations.isEmpty() || declarations.size() > MAX_FILES * 2) {
            throw new UploadException("请提供文件清单，每批最多 5000 份有效 JSON。");
        }
        List<UploadFile> files = new ArrayList<>();
        java.util.Set<String> names = new java.util.HashSet<>();
        int ignored = 0;
        long totalBytes = 0;
        for (UploadDeclaration declaration : declarations) {
            String name = inputName(declaration.filename());
            if (ignoredPath(name)) {
                ignored++;
                continue;
            }
            if (declaration.size() <= 0 || declaration.size() > maxFileBytes) {
                throw new UploadException("文件 " + name + " 为空或超过单文件大小限制。", PAYLOAD_TOO_LARGE);
            }
            if (declaration.lastModified() < 0) {
                throw new UploadException("文件修改时间格式错误。");
            }
            if (!names.add(name)) {
                throw new UploadException("文件路径重复：" + name + "。请保留不同分片的相对路径。");
            }
            totalBytes += declaration.size();
            files.add(new UploadFile(files.size(), name, declaration.size(), declaration.lastModified()));
        }
        if (files.isEmpty()) {
            throw new UploadException("所选文件均为 .ipynb_checkpoints 备份，已全部忽略。");
        }
        if (files.size() > MAX_FILES || totalBytes > maxBatchBytes) {
            throw new UploadException("所选文件超过单批数量或总大小限制，请分批导入。", PAYLOAD_TOO_LARGE);
        }
        long activeBytes = sessions.values().stream().filter(Session::reserved).mapToLong(Session::totalBytes).sum();
        long activeSessions = sessions.values().stream().filter(Session::reserved).count();
        if (activeSessions >= MAX_SESSIONS) {
            throw new UploadException("暂存中的导入批次较多，请完成或取消已有批次。", CONFLICT);
        }
        if (activeBytes + totalBytes > maxStagedBytes) {
            throw new UploadException("服务器上传暂存额度不足，请先完成已有批次。", PAYLOAD_TOO_LARGE);
        }
        String id = UUID.randomUUID().toString().replace("-", "");
        Path directory = workspace.resolve(id).normalize();
        ensureChild(directory);
        try {
            Files.createDirectory(directory);
        } catch (IOException exception) {
            throw new UploadException("无法创建上传会话。", INTERNAL_SERVER_ERROR);
        }
        Session session = new Session(id, line, files, totalBytes, ignored, directory);
        sessions.put(id, session);
        return status(session);
    }

    public synchronized Map<String, Object> get(String id) {
        expire();
        return status(required(id));
    }

    public Map<String, Object> upload(String id, int index, InputStream input, long declaredLength) {
        Session session;
        UploadFile file;
        Path partial;
        synchronized (this) {
            expire();
            session = required(id);
            if (!"uploading".equals(session.status) || session.inflight != null) {
                throw new UploadException("当前批次正在处理或上传中，请稍后重试。", CONFLICT);
            }
            if (index < 0 || index >= session.files.size()) {
                throw new UploadException("文件序号不存在。", NOT_FOUND);
            }
            file = session.files.get(index);
            if (file.uploaded) {
                throw new UploadException("该文件已上传完成，请刷新上传进度。", CONFLICT);
            }
            if (declaredLength >= 0 && declaredLength != file.size) {
                throw new UploadException("上传长度与文件清单不一致，请重新选择文件。");
            }
            session.inflight = index;
            session.received = 0;
            session.touched = Instant.now();
            partial = session.directory.resolve(index + ".part");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long received = copyBounded(input, partial, file.size, digest, session, index);
            if (received != file.size) {
                throw new UploadException("文件上传不完整，请重试当前文件。");
            }
            synchronized (this) {
                Files.move(partial, session.directory.resolve(index + ".json"), StandardCopyOption.REPLACE_EXISTING);
                file.uploaded = true;
                file.sha256 = HexFormat.of().formatHex(digest.digest());
                session.inflight = null;
                session.received = 0;
                session.touched = Instant.now();
                return result("index", index, "uploaded", true, "sha256", file.sha256);
            }
        } catch (UploadException exception) {
            abort(session, index, partial);
            throw exception;
        } catch (IOException | NoSuchAlgorithmException exception) {
            abort(session, index, partial);
            throw new UploadException("上传文件写入失败。", INTERNAL_SERVER_ERROR);
        }
    }

    public synchronized Map<String, Object> submit(String id, boolean dryRun, ImportJobs jobs) {
        expire();
        Session session = required(id);
        if ("processing".equals(session.status)) {
            return result("id", session.jobId, "existing", true);
        }
        if (!List.of("uploading", "validated", "failed").contains(session.status)) {
            throw new UploadException("该批次已完成或已取消，请新建上传批次。", CONFLICT);
        }
        if (session.inflight != null || session.files.stream().anyMatch(file -> !file.uploaded)) {
            throw new UploadException("请先上传本批全部文件。", CONFLICT);
        }
        List<ImportFile> imports = new ArrayList<>();
        try {
            for (UploadFile file : session.files) {
                imports.add(new ImportFile(session.line, file.filename,
                        Files.readString(session.directory.resolve(file.index + ".json"))));
            }
        } catch (IOException exception) {
            throw new UploadException("读取上传文件失败。", INTERNAL_SERVER_ERROR);
        }
        try {
            String jobId = jobs.submit(imports, dryRun, outcome -> finish(session.id, dryRun, outcome));
            session.status = "processing";
            session.jobId = jobId;
            session.touched = Instant.now();
            return result("id", jobId, "existing", false);
        } catch (IllegalStateException exception) {
            throw new UploadException(exception.getMessage(), CONFLICT);
        }
    }

    public synchronized Map<String, Object> cancel(String id) {
        Session session = required(id);
        if ("processing".equals(session.status)) {
            throw new UploadException("后台正在校验或提交，请等待结果；关闭窗口不会中断任务。", CONFLICT);
        }
        session.status = "cancelled";
        removeFiles(session);
        return result("id", id, "status", "cancelled");
    }

    private long copyBounded(InputStream input, Path target, long maximum, MessageDigest digest,
                             Session session, int index) throws IOException {
        long total = 0;
        byte[] buffer = new byte[8192];
        try (java.io.OutputStream output = Files.newOutputStream(target)) {
            int count;
            while ((count = input.read(buffer)) >= 0) {
                total += count;
                if (total > maximum) {
                    throw new UploadException("文件实际大小超过清单声明，已停止上传。", PAYLOAD_TOO_LARGE);
                }
                output.write(buffer, 0, count);
                digest.update(buffer, 0, count);
                synchronized (this) {
                    if (!Integer.valueOf(index).equals(session.inflight)) {
                        throw new UploadException("上传已取消。", CONFLICT);
                    }
                    session.received = total;
                    session.touched = Instant.now();
                }
            }
        }
        return total;
    }

    private synchronized void finish(String id, boolean dryRun, String outcome) {
        Session session = sessions.get(id);
        if (session == null) {
            return;
        }
        session.status = "succeeded".equals(outcome) ? (dryRun ? "validated" : "succeeded") : "failed";
        session.touched = Instant.now();
        if ("succeeded".equals(session.status)) {
            removeFiles(session);
        }
    }

    private synchronized void abort(Session session, int index, Path partial) {
        try {
            Files.deleteIfExists(partial);
        } catch (IOException ignored) {
            // A later session cleanup retries deletion.
        }
        if (Integer.valueOf(index).equals(session.inflight)) {
            session.inflight = null;
            session.received = 0;
            session.touched = Instant.now();
        }
    }

    private void expire() {
        Instant threshold = Instant.now().minus(Duration.ofSeconds(expiresSeconds));
        List<String> expired = sessions.values().stream()
                .filter(session -> !"processing".equals(session.status) && session.inflight == null)
                .filter(session -> session.touched.isBefore(threshold)).map(Session::id).toList();
        expired.forEach(id -> {
            removeFiles(sessions.get(id));
            sessions.remove(id);
        });
    }

    private Session required(String id) {
        Session result = sessions.get(id);
        if (result == null) {
            throw new UploadException("上传会话不存在或已过期，请重新选择文件。", NOT_FOUND);
        }
        return result;
    }

    private Map<String, Object> status(Session session) {
        List<Map<String, Object>> fileRows = session.files.stream().map(file -> result("index", file.index,
                "filename", file.filename, "size", file.size, "last_modified", file.lastModified,
                "uploaded", file.uploaded, "sha256", file.sha256)).toList();
        long uploadedBytes = session.files.stream().filter(file -> file.uploaded).mapToLong(file -> file.size).sum();
        long uploaded = session.files.stream().filter(file -> file.uploaded).count();
        return result("id", session.id, "line", session.line.code(), "status", session.status,
                "total", session.files.size(), "total_bytes", session.totalBytes, "ignored", session.ignored,
                "job_id", session.jobId, "uploaded", uploaded, "uploaded_bytes", uploadedBytes,
                "received", session.received, "inflight", session.inflight, "files", fileRows);
    }

    private String inputName(String value) {
        String normalized = value == null ? "" : value.replace('\\', '/');
        if (normalized.isBlank() || normalized.length() > 400 || normalized.startsWith("/")
                || normalized.contains(":") || normalized.endsWith("/") || !normalized.toLowerCase().endsWith(".json")) {
            throw new UploadException("请提供目录内有效的 JSON 相对文件名，不能包含上级路径或盘符。");
        }
        for (String part : normalized.split("/", -1)) {
            if (part.isBlank() || part.equals(".") || part.equals("..")
                    || part.chars().anyMatch(character -> character < 32)) {
                throw new UploadException("请提供目录内有效的 JSON 相对文件名，不能包含上级路径或盘符。");
            }
        }
        return normalized;
    }

    private boolean ignoredPath(String value) {
        return java.util.Arrays.stream(value.split("/")).anyMatch(part -> part.equalsIgnoreCase(".ipynb_checkpoints"));
    }

    private void ensureChild(Path path) {
        if (!path.startsWith(workspace) || path.equals(workspace)) {
            throw new UploadException("上传临时目录校验失败。", INTERNAL_SERVER_ERROR);
        }
    }

    private void removeFiles(Session session) {
        if (session == null || !session.reserved) {
            return;
        }
        ensureChild(session.directory);
        try (java.util.stream.Stream<Path> paths = Files.walk(session.directory)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException exception) {
                    throw new UploadException("清理上传临时文件失败。", INTERNAL_SERVER_ERROR);
                }
            });
            session.reserved = false;
        } catch (IOException exception) {
            throw new UploadException("清理上传临时文件失败。", INTERNAL_SERVER_ERROR);
        }
    }

    @Override
    public synchronized void close() {
        sessions.values().forEach(this::removeFiles);
        try {
            Files.deleteIfExists(workspace);
        } catch (IOException ignored) {
            // Process shutdown will leave an isolated worker directory for operational cleanup.
        }
    }

    private static long positive(long value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException("Upload setting must be positive: " + name);
        }
        return value;
    }

    private static Map<String, Object> result(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            result.put(String.valueOf(values[index]), values[index + 1]);
        }
        return result;
    }

    public record UploadDeclaration(String filename, long size, long lastModified) { }

    private static final class UploadFile {
        private final int index;
        private final String filename;
        private final long size;
        private final long lastModified;
        private boolean uploaded;
        private String sha256 = "";

        private UploadFile(int index, String filename, long size, long lastModified) {
            this.index = index;
            this.filename = filename;
            this.size = size;
            this.lastModified = lastModified;
        }
    }

    private static final class Session {
        private final String id;
        private final BusinessLine line;
        private final List<UploadFile> files;
        private final long totalBytes;
        private final int ignored;
        private final Path directory;
        private String status = "uploading";
        private Integer inflight;
        private long received;
        private boolean reserved = true;
        private Instant touched = Instant.now();
        private String jobId;

        private Session(String id, BusinessLine line, List<UploadFile> files, long totalBytes,
                        int ignored, Path directory) {
            this.id = id;
            this.line = line;
            this.files = files;
            this.totalBytes = totalBytes;
            this.ignored = ignored;
            this.directory = directory;
        }

        private String id() { return id; }
        private long totalBytes() { return totalBytes; }
        private boolean reserved() { return reserved; }
    }
}
