"use strict";

// Upload the File itself: never read the entire batch into browser strings.
class LineageImportController {
  constructor(onImported) {
    this.onImported = onImported;
    this.files = []; this.hasSelection = false; this.ignored = 0; this.session = null; this.busy = false;
    this.phase = ""; this.xhr = null; this.cancelled = false; this.failure = null; this.initError = "";
    this.storageKey = "lineage-upload-v6";
    this.byId = id => document.getElementById(id);
  }
  async request(path, method = "GET", body) {
    const response = await fetch("/api/" + path, {
      method, headers: body === undefined ? {} : {"Content-Type": "application/json"},
      body: body === undefined ? undefined : JSON.stringify(body)
    });
    let data;
    try { data = await response.json(); } catch { data = {detail: "服务响应异常，请检查连接后重试。"}; }
    if (!response.ok) {
      const error = new Error(typeof data.detail === "string" ? data.detail : data.issues?.[0]?.message || "请求失败，请稍后重试。");
      error.status = response.status; error.data = data; throw error;
    }
    return data;
  }
  remember(id) {
    try { id ? localStorage.setItem(this.storageKey, id) : localStorage.removeItem(this.storageKey); } catch { /* Storage is optional. */ }
  }
  size(bytes) { return (bytes / 1024 / 1024).toFixed(2) + " MiB"; }
  filename(file) {
    const parts = (file.webkitRelativePath || file.name).replaceAll("\\", "/").split("/");
    return file.webkitRelativePath ? parts.slice(1).join("/") : parts.join("/");
  }
  ignoredFile(file) {
    return (file.webkitRelativePath || file.name).replaceAll("\\", "/").split("/")
      .some(part => part.toLowerCase() === ".ipynb_checkpoints");
  }
  async init() {
    this.byId("import-cancel").onclick = () => this.cancel();
    this.byId("import-reset").onclick = () => this.reset();
    this.byId("import-errors").onclick = () => this.downloadErrors();
    try {
      this.limits = await this.request("import-limits");
      this.byId("import-limits").textContent = "每批最多 " + this.limits.max_files + " 份、合计 " +
        this.size(this.limits.max_batch_bytes) + "；单文件最多 " + this.size(this.limits.max_file_bytes) + "。";
      let saved; try { saved = localStorage.getItem(this.storageKey); } catch { /* optional */ }
      if (saved) {
        try {
          this.session = await this.request("import-sessions/" + saved);
          this.byId("import-line").value = this.session.line;
          this.selectionInfo();
          this.transferSummary(this.session.uploaded, this.session.total, this.session.uploaded_bytes, this.session.total_bytes);
          if (this.session.status === "processing") {
            this.setBusy(true, "validating");
            void this.followJob(this.session.job_id).catch(e => this.showError(e)).finally(() => this.setBusy(false));
          } else if (this.session.status === "succeeded" || this.session.status === "cancelled") {
            this.session = null; this.remember(null); this.selectionInfo();
          } else {
            this.progress("ready", "已恢复暂存进度，上传完成后还需校验和确认导入。", this.session.uploaded_bytes, this.session.total_bytes);
            this.byId("import-result").textContent = this.session.uploaded === this.session.total
              ? "已恢复暂存文件，可以直接校验或确认导入。"
              : "已恢复 " + this.session.uploaded + " 份已上传文件。重新选择同一目录后，可继续上传剩余文件。";
          }
        } catch (e) { if (e.status === 404) this.remember(null); else this.showError(e); }
      }
    } catch (e) {
      this.initError = e.status === 404
        ? "导入功能已更新，请重启应用服务后刷新页面。"
        : "暂时无法取得上传限制，请检查服务连接后刷新页面。";
      this.byId("import-limits").textContent = this.initError;
      this.showError(new Error(this.initError));
    }
  }
  choose(event) {
    if (this.busy) return;
    this.hasSelection = true;
    const jsonFiles = [...event.target.files].filter(f => f.name.toLowerCase().endsWith(".json"));
    this.files = jsonFiles.filter(f => !this.ignoredFile(f)).sort((a, b) => this.filename(a).localeCompare(this.filename(b)));
    this.ignored = jsonFiles.length - this.files.length;
    this.failure = null; this.byId("import-errors").hidden = true;
    this.selectionInfo();
    const error = this.selectionError();
    this.byId("import-result").textContent = error || "文件已选择。校验或确认导入时将逐份上传，上传完成后统一校验。";
    this.byId("import-files").disabled = !!error;
    this.byId("validate-files").disabled = !!error;
    if (error) this.byId("import-progress").hidden = true;
    else {
      const total = this.files.reduce((n, file) => n + file.size, 0);
      this.transferSummary(0, this.files.length, 0, total);
      this.progress("ready", "点击“校验数据”或“确认导入”开始上传。", 0, total);
    }
  }
  selectionInfo() {
    const files = this.hasSelection ? this.files : this.session?.files || [];
    const total = files.reduce((n, f) => n + f.size, 0), largest = files.reduce((n, f) => Math.max(n, f.size), 0);
    this.byId("file-count").textContent = files.length ? "已选择 " + files.length + " 份 JSON · 合计 " + this.size(total) +
      " · 最大文件 " + this.size(largest) + (this.ignored ? " · 已忽略备份 " + this.ignored + " 份" : "")
      : this.ignored ? "已忽略 .ipynb_checkpoints 中 " + this.ignored + " 份备份，没有可导入文件。" : "尚未选择文件";
  }
  selectionError() {
    if (!this.limits) return this.initError || "暂时无法取得上传限制，请刷新后重试。";
    const files = this.hasSelection ? this.files : this.session?.files || [];
    if (!files.length) return "请先选择有效 JSON 文件或目录。";
    if (files.length > this.limits.max_files) return "文件数量超过本批上限，请分批选择。";
    if (files.some(f => !f.size || f.size > this.limits.max_file_bytes)) return "存在空文件或超过 " + this.size(this.limits.max_file_bytes) + " 的单个文件，请检查后重新选择。";
    if (files.reduce((n, f) => n + f.size, 0) > this.limits.max_batch_bytes) return "文件合计超过 " + this.size(this.limits.max_batch_bytes) + "，请分批选择。";
    return "";
  }
  setBusy(value, phase = this.phase) {
    this.busy = value; this.phase = phase;
    for (const id of ["validate-files", "import-files", "import-line", "file-input", "directory-input", "import-reset"]) {
      this.byId(id).disabled = value;
    }
    this.byId("import-cancel").hidden = !value || phase !== "uploading";
  }
  progress(phase, text, current, total) {
    this.phase = phase;
    const panel = this.byId("import-progress");
    panel.hidden = false; panel.dataset.phase = phase; panel.dataset.state = "running";
    const names = {ready:"待上传 / 待确认", preparing:"准备上传", uploading:"上传文件", queued:"等待处理", validating:"校验文件", writing:"写入数据", building:"构建血缘图", committing:"提交结果", complete:"处理完成"};
    this.byId("import-progress-title").textContent = phase === "complete" ? text : names[phase] || "处理中";
    if (phase === "ready") this.byId("import-progress-title").textContent = current === 0 ? "待上传" : current === total ? "文件已上传，待确认" : "待继续上传";
    const steps = ["uploading", "validating", "writing", "building", "committing"];
    const index = steps.indexOf(phase);
    document.querySelectorAll("#import-steps li").forEach((node, i) => {
      node.classList.toggle("active", i === index); node.classList.toggle("done", i < index || phase === "complete");
    });
    const bar = this.byId("import-progress-bar");
    if (total > 0) {
      bar.max = total; bar.value = Math.max(0, Math.min(current, total));
      this.byId("import-progress-percent").textContent = Math.floor(bar.value / total * 100) + "%";
    } else {
      bar.removeAttribute("value");
      this.byId("import-progress-percent").textContent = "处理中";
    }
    if (phase !== "uploading") this.byId("import-current-file").hidden = true;
    this.byId("import-progress-text").textContent = text;
    this.byId("import-cancel").hidden = !this.busy || phase !== "uploading";
  }
  transferSummary(completed, count, bytes, totalBytes, filename = "") {
    this.byId("import-upload-count").textContent = "已上传 " + completed + " / " + count + " 份";
    this.byId("import-upload-bytes").textContent = "已传输 " + this.size(bytes) + " / " + this.size(totalBytes);
    const current = this.byId("import-current-file");
    current.textContent = filename ? "当前文件：" + filename : "";
    current.hidden = !filename;
  }
  uploadProgress(item = null, loaded = 0) {
    const s = this.session;
    const bytes = s.uploaded_bytes + (item ? Math.min(loaded, item.size) : 0);
    this.transferSummary(s.uploaded, s.total, bytes, s.total_bytes, item?.filename);
    const text = item ? (loaded >= item.size ? "文件已发出，等待服务器确认。" : "正在上传第 " + (item.index + 1) + " / " + s.total + " 份文件。")
      : s.uploaded === s.total ? "文件已上传，接下来进行校验；尚未写入数据库。" : "正在准备上传剩余文件。";
    this.progress("uploading", text, bytes, s.total_bytes);
  }
  pauseProgress(title, state = "error") {
    this.byId("import-progress").dataset.state = state;
    this.byId("import-progress-title").textContent = title;
    const bar = this.byId("import-progress-bar");
    if (!bar.hasAttribute("value")) { bar.value = 0; this.byId("import-progress-percent").textContent = "—"; }
    document.querySelectorAll("#import-steps li").forEach(node => node.classList.remove("active"));
  }
  async ensureSession() {
    const line = this.byId("import-line").value;
    if (!line) throw new Error("请选择这些文件所属的条线。");
    if (this.session) {
      try { this.session = await this.request("import-sessions/" + this.session.id); }
      catch (e) { if (e.status === 404) { this.session = null; this.remember(null); } else throw e; }
    }
    if (this.session?.status === "processing") return;
    const manifest = this.files.map(f => ({filename: this.filename(f), size: f.size, last_modified: f.lastModified}));
    const reusable = this.session && this.session.line === line &&
      !["succeeded", "cancelled"].includes(this.session.status) &&
      (!manifest.length || (manifest.length === this.session.files.length && manifest.every((f, i) => {
        const previous = this.session.files[i];
        return f.filename === previous.filename && f.size === previous.size && f.last_modified === previous.last_modified;
      })));
    if (reusable) return;
    if (this.session) await this.request("import-sessions/" + this.session.id, "DELETE");
    this.session = null; this.remember(null);
    if (!manifest.length) throw new Error("暂存文件已过期，请重新选择文件。");
    this.session = await this.request("import-sessions?line=" + encodeURIComponent(line), "POST", {documents: manifest});
    this.remember(this.session.id);
  }
  uploadOne(file, index) {
    return new Promise((resolve, reject) => {
      const xhr = new XMLHttpRequest(); this.xhr = xhr;
      xhr.open("PUT", "/api/import-sessions/" + this.session.id + "/files/" + index);
      xhr.setRequestHeader("Content-Type", "application/octet-stream");
      xhr.timeout = 600000;
      xhr.upload.onprogress = event => {
        if (!this.cancelled && this.session) this.uploadProgress(this.session.files[index], event.loaded);
      };
      xhr.onload = () => {
        this.xhr = null;
        if (xhr.status >= 200 && xhr.status < 300) resolve();
        else {
          let detail; try { detail = JSON.parse(xhr.responseText).detail; } catch { /* proxy error */ }
          reject(new Error(typeof detail === "string" ? detail : "当前文件上传失败，已完成的文件会保留。"));
        }
      };
      xhr.onerror = xhr.ontimeout = () => { this.xhr = null; reject(new Error("上传连接中断。可点击确认导入，继续上传未完成的文件。")); };
      xhr.onabort = () => { this.xhr = null; reject(new Error("上传已取消。")); };
      xhr.send(file);
    });
  }
  async start(dryRun) {
    if (this.busy) return;
    const selectionError = this.selectionError();
    if (selectionError) { this.showError(new Error(selectionError)); return; }
    this.cancelled = false; this.failure = null; this.byId("import-errors").hidden = true;
    this.setBusy(true, "uploading");
    this.progress("preparing", "正在准备上传批次…", 0, 0);
    try {
      await this.ensureSession();
      if (this.cancelled) { if (this.session) await this.request("import-sessions/" + this.session.id, "DELETE"); return; }
      if (this.session.status === "processing") { await this.followJob(this.session.job_id); return; }
      this.byId("import-result").textContent = "上传完成的文件保留在本批次中；上传中断后可继续。全部校验通过后才会写入数据库。";
      this.uploadProgress();
      for (const item of this.session.files) {
        if (this.cancelled) return;
        if (item.uploaded) continue;
        const file = this.files[item.index];
        if (!file) throw new Error("请重新选择原来的文件或目录，以继续剩余上传。");
        this.uploadProgress(item);
        try { await this.uploadOne(file, item.index); }
        catch (e) {
          // A lost HTTP acknowledgement may still mean the file reached disk.
          if (!this.cancelled) {
            const status = await this.request("import-sessions/" + this.session.id);
            if (!status.files[item.index].uploaded) { e.filename = item.filename; throw e; }
          } else throw e;
        }
        if (this.cancelled) return;
        item.uploaded = true; this.session.uploaded += 1; this.session.uploaded_bytes += item.size;
        this.uploadProgress();
      }
      if (this.cancelled) return;
      this.setBusy(true, "validating");
      this.progress("validating", "文件已上传，正在提交校验任务…", 0, 0);
      const job = await this.request("import-sessions/" + this.session.id + "/submit?dry_run=" + dryRun, "POST");
      await this.followJob(job.id);
    } catch (e) {
      if (!this.cancelled) this.showError(e);
    } finally { this.setBusy(false); }
  }
  async followJob(id) {
    for (;;) {
      let job;
      try { job = await this.request("import-jobs/" + id); }
      catch (e) { e.message += " 后台任务可能仍在运行，重新打开导入窗口或点击确认导入可继续查看。"; throw e; }
      const names = {queued:"等待处理", validating:"校验文件", writing:"写入数据", building:"构建血缘图", committing:"提交结果", complete:"处理完成"};
      const total = job.phase === "validating" ? job.total : job.phase_total || 0;
      const current = job.phase_processed ?? job.processed;
      this.progress(job.phase, (names[job.phase] || "处理中") + (total ? " · " + current + " / " + total : "") +
        (job.filename ? " · " + job.filename : ""), current, total);
      if (job.status === "failed") {
        this.pauseProgress(job.phase === "validating" ? "校验失败" : "导入失败");
        this.failure = job;
        this.byId("import-errors").hidden = !(job.issues?.length || job.failed_files?.length);
        let details = job.error + (job.failed_files?.length ? "\n失败文件 " + job.failed_files.length + " 份：\n" + job.failed_files.slice(0,20).join("\n") : "");
        if (job.issues?.length) details += "\n\n" + job.issues.slice(0,5).map(i => (i.filename || i.document || "") + "：" + i.message).join("\n");
        this.byId("import-result").textContent = details;
        return;
      }
      if (job.status === "succeeded") {
        const r = job.result;
        this.byId("import-result").textContent = (job.dry_run ? "校验通过，尚未写入数据。可以直接点击确认导入，无需重复上传。\n预计：" : "整批导入成功。\n") +
          "新增 " + r.added + " 份，更新 " + r.updated + " 份，未变化 " + r.unchanged + " 份，失败 0 份。" +
          (job.dry_run ? "\n完整跨文件血缘分析将在正式导入后生成。" : "\n当前共 " + r.stats.documents + " 份文档、" + r.stats.tables + " 张表。") +
          (r.issues_returned ? "\n另有数据提示，可在对象详情中核对；这些提示不影响本次成功结果。" : "");
        this.progress("complete", job.dry_run ? "校验完成，等待确认导入" : "导入完成", 1, 1);
        if (job.dry_run) {
          // Validation did not execute the write/build/commit stages.
          document.querySelectorAll("#import-steps li").forEach((node, i) => node.classList.toggle("done", i < 2));
          this.session.status = "validated";
        } else {
          this.remember(null); this.session = null;
          try { await this.onImported(); } catch { this.byId("import-result").textContent += "\n数据已提交，页面刷新失败，请刷新页面查看结果。"; }
        }
        return;
      }
      await new Promise(resolve => setTimeout(resolve, 700));
    }
  }
  showError(error) {
    if (this.phase === "uploading" && this.session) this.uploadProgress();
    this.pauseProgress(this.phase === "uploading" ? "上传已暂停" : "处理暂停");
    this.byId("import-result").textContent = (error.filename ? "文件：" + error.filename + "\n" : "") + error.message;
    this.byId("import-progress-text").textContent = "处理暂停，请查看下方提示。";
  }
  async cancel() {
    this.cancelled = true;
    if (this.xhr) this.xhr.abort();
    try {
      if (this.session) await this.request("import-sessions/" + this.session.id, "DELETE");
      this.remember(null); this.session = null;
      this.pauseProgress("上传已取消", "cancelled");
      this.byId("import-progress-text").textContent = "本次上传已取消，未写入数据库。";
      this.byId("import-result").textContent = "本次上传已取消，未写入数据库。已上传的临时文件会清理。";
    } catch (e) { this.showError(e); }
  }
  async reset() {
    if (this.busy) return;
    try {
      if (this.session) await this.request("import-sessions/" + this.session.id, "DELETE");
    } catch (e) { if (e.status !== 404) { this.showError(e); return; } }
    this.session = null; this.remember(null); this.files = []; this.hasSelection = false; this.ignored = 0;
    this.byId("file-input").value = ""; this.byId("directory-input").value = "";
    this.byId("import-progress").hidden = true; this.byId("import-errors").hidden = true;
    this.selectionInfo(); this.setBusy(false);
    this.byId("import-result").textContent = "请重新选择文件或目录。";
  }
  downloadErrors() {
    if (!this.failure) return;
    const lines = ["导入失败清单", "本批未写入。", "", ...(this.failure.failed_files || []), "",
      ...(this.failure.issues || []).map(i => [i.filename || i.document, i.location, i.message].filter(Boolean).join(" | ")),
      "", "详细错误最多列出 200 条；失败文件清单列出本批发现的全部失败文件。"];
    const url = URL.createObjectURL(new Blob(["\ufeff" + lines.join("\r\n")], {type:"text/plain;charset=utf-8"}));
    const a = document.createElement("a"); a.href = url; a.download = "导入失败清单.txt"; a.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  }
}
window.LineageImportController = LineageImportController;




