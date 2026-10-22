"use strict";

class LineageDocumentManager {
  constructor(onChanged, isImportBusy) {
    this.onChanged = onChanged; this.isImportBusy = isImportBusy;
    this.selected = new Map(); this.items = []; this.offset = 0; this.limit = 30; this.total = 0;
    this.busy = false; this.preview = null; this.previewOffset = 0; this.revision = 0;
    this.job = null; this.storageKey = "lineage-reassignment-v6";
    this.byId = id => document.getElementById(id);
  }
  message(text, error = false) {
    this.byId("files-message").textContent = text;
    this.byId("files-message").classList.toggle("error", error);
  }
  act(fn) { return (...args) => Promise.resolve().then(() => fn(...args)).catch(e => this.message(e.message, true)); }
  remember(id) {
    this.job = id;
    try { id ? localStorage.setItem(this.storageKey, id) : localStorage.removeItem(this.storageKey); } catch { /* optional */ }
  }
  init() {
    this.byId("files-open").onclick = this.act(() => this.open());
    this.byId("files-line").onchange = this.act(() => this.filter());
    let timer;
    this.byId("files-search").oninput = () => {
      ++this.revision; this.selected.clear(); this.invalidate();
      clearTimeout(timer); timer = setTimeout(this.act(() => this.filter()), 220);
    };
    this.byId("files-refresh").onclick = this.act(() => this.filter());
    this.byId("files-prev").onclick = this.act(() => { this.offset -= this.limit; return this.load(); });
    this.byId("files-next").onclick = this.act(() => { this.offset += this.limit; return this.load(); });
    this.byId("files-clear").onclick = () => { this.selected.clear(); this.invalidate(); this.render(); };
    this.byId("files-page-select").onchange = event => {
      for (const item of this.items) event.target.checked ? this.selected.set(item.dataset_id, item) : this.selected.delete(item.dataset_id);
      this.invalidate(); this.render();
    };
    this.byId("files-select-all").onclick = this.act(() => this.selectAll());
    this.byId("files-target").onchange = () => this.invalidate();
    this.byId("files-check").onclick = this.act(() => this.check());
    this.byId("files-confirm").onclick = this.act(() => this.confirm());
    this.byId("files-preview-prev").onclick = () => { this.previewOffset -= this.limit; this.renderPreview(); };
    this.byId("files-preview-next").onclick = () => { this.previewOffset += this.limit; this.renderPreview(); };
    try { this.job = localStorage.getItem(this.storageKey); } catch { /* optional */ }
    this.controls();
  }
  async open() {
    this.byId("files-dialog").showModal();
    if (this.busy) return;
    if (this.job) return this.follow(this.job);
    this.selected.clear(); this.invalidate();
    await this.load();
  }
  invalidate() {
    this.preview = null; this.byId("files-preview").hidden = true;
    this.controls();
  }
  controls() {
    this.byId("files-selected").textContent = "已选择 " + this.selected.size + " 份";
    for (const input of this.byId("files-dialog").querySelectorAll("input, select, button:not([data-close])")) input.disabled = this.busy;
    this.byId("files-check").disabled = this.busy || !this.selected.size || !this.byId("files-target").value;
    this.byId("files-confirm").disabled = this.busy || !this.preview?.can_apply;
    this.byId("files-select-all").disabled = this.busy || !this.total || this.total > 5000;
    this.byId("files-clear").disabled = this.busy || !this.selected.size;
    this.byId("files-prev").disabled = this.busy || !this.offset;
    this.byId("files-next").disabled = this.busy || this.offset + this.limit >= this.total;
    this.byId("files-preview-prev").disabled = this.busy || !this.previewOffset;
    this.byId("files-preview-next").disabled = this.busy || !this.preview || this.previewOffset + this.limit >= this.preview.items.length;
    const checked = this.items.filter(item => this.selected.has(item.dataset_id)).length;
    this.byId("files-page-select").checked = this.items.length > 0 && checked === this.items.length;
    this.byId("files-page-select").indeterminate = checked > 0 && checked < this.items.length;
  }
  async filter() {
    if (this.busy) return;
    this.offset = 0; this.selected.clear(); this.invalidate();
    await this.load();
  }
  params(limit = this.limit) {
    return {line:this.byId("files-line").value, q:this.byId("files-search").value, offset:this.offset, limit};
  }
  async load() {
    const revision = ++this.revision;
    const [result, lines] = await Promise.all([api("document-files", this.params()), api("lines")]);
    if (revision !== this.revision) return;
    if (this.offset && this.offset >= result.total) { this.offset = 0; return this.load(); }
    this.items = result.items; this.total = result.total;
    this.byId("files-count").textContent = "符合条件 " + result.total + " 份 / 已导入 " + result.all_total + " 份";
    this.byId("files-line-summary").replaceChildren(...lines.items.map(item => {
      const card = el("div");
      card.append(el("strong", item.name), el("span", item.documents.toLocaleString() + " 份 JSON"), el("span", item.tables.toLocaleString() + " 张目标表"));
      return card;
    }));
    this.render();
  }
  render() {
    const body = this.byId("files-items"); body.replaceChildren();
    for (const item of this.items) {
      const row = el("tr"); row.dataset.id = item.dataset_id;
      row.classList.toggle("selected", this.selected.has(item.dataset_id));
      const choose = document.createElement("input"); choose.type = "checkbox";
      choose.checked = this.selected.has(item.dataset_id); choose.setAttribute("aria-label", "选择 " + item.filename);
      choose.onchange = () => { choose.checked ? this.selected.set(item.dataset_id, item) : this.selected.delete(item.dataset_id); this.invalidate(); this.render(); };
      const cell = el("td"); cell.append(choose);
      const filename = el("td", item.filename); filename.append(el("small", item.script_name));
      const action = el("button", "更正条线");
      action.onclick = () => {
        this.selected.clear(); this.selected.set(item.dataset_id, item); this.invalidate(); this.render();
        this.byId("files-target").focus(); this.message("已选择 " + item.filename + "，请选择更正后的条线，再点击“检查更正”。");
      };
      const actions = el("td"); actions.append(action);
      row.append(cell, filename, el("td", item.line.toUpperCase()), actions); body.append(row);
    }
    this.byId("files-empty").hidden = this.items.length > 0;
    this.byId("files-page").textContent = Math.floor(this.offset / this.limit) + 1 + " / " + Math.max(1, Math.ceil(this.total / this.limit));
    this.controls();
  }
  async selectAll() {
    if (this.busy) return;
    this.busy = true; this.controls();
    try {
      const result = await api("document-files", {...this.params(5000), offset:0});
      if (result.total > 5000) throw new Error("筛选结果超过 5000 份，请缩小筛选范围后分批更正。");
      this.selected = new Map(result.items.map(item => [item.dataset_id, item])); this.invalidate(); this.render();
    } finally { this.busy = false; this.controls(); }
  }
  payload() {
    return {documents:[...this.selected.values()].map(item => ({dataset_id:item.dataset_id, hash:item.hash})), target_line:this.byId("files-target").value};
  }
  async check() {
    if (this.busy) return;
    if (this.isImportBusy()) throw new Error("导入窗口正在处理，请等待完成后再更正文件归属。");
    this.invalidate(); this.busy = true; this.controls(); this.byId("files-progress").hidden = true;
    try {
      this.message("正在检查文件归属和目标条线中的冲突…");
      this.preview = await api("document-files/reassign/preview", {}, this.payload());
      this.previewOffset = 0; this.renderPreview();
      this.message(this.preview.conflicts ? "存在冲突，本批尚未更正。请调整文件选择后重新检查。"
        : this.preview.changed ? "检查完成，尚未更正。核对原条线与新条线后，点击“确认更正”。" : "所选文件已属于该条线，无需更正。", !!this.preview.conflicts);
    } finally { this.busy = false; this.controls(); }
  }
  renderPreview() {
    const p = this.preview; if (!p) return;
    this.byId("files-preview").hidden = false;
    this.byId("files-preview-summary").textContent = "检查 " + p.total + " 份：可更正 " + p.changed + " 份，无需更正 " + p.unchanged + " 份，冲突或已变化 " + p.conflicts + " 份。目标条线：" + p.target_line.toUpperCase() + "。";
    const items = [...p.items].sort((a,b) => Number(["conflict","stale"].includes(b.status)) - Number(["conflict","stale"].includes(a.status)));
    const body = this.byId("files-preview-items"); body.replaceChildren();
    for (const item of items.slice(this.previewOffset, this.previewOffset + this.limit)) {
      const row = el("tr", null, item.status);
      row.append(el("td", item.filename), el("td", item.from_line.toUpperCase() || "已变化"), el("td", item.to_line.toUpperCase()), el("td", item.reason));
      body.append(row);
    }
    this.byId("files-preview-page").textContent = Math.floor(this.previewOffset / this.limit) + 1 + " / " + Math.max(1, Math.ceil(items.length / this.limit));
    this.controls();
  }
  async confirm() {
    if (this.busy || !this.preview?.can_apply) return;
    if (this.isImportBusy()) throw new Error("导入窗口正在处理，请等待完成后重新检查更正结果。");
    this.busy = true; this.controls();
    try {
      const job = await api("document-files/reassign", {}, {...this.payload(), preview_token:this.preview.preview_token});
      this.remember(job.id); await this.follow(job.id);
    } catch (e) { this.invalidate(); throw e; }
    finally { this.busy = false; this.controls(); }
  }
  async follow(id) {
    this.busy = true; this.controls(); this.byId("files-progress").hidden = false;
    try {
      for (;;) {
        let job;
        try { job = await api("import-jobs/" + id); }
        catch (e) {
          if (/任务不存在/.test(e.message)) this.remember(null);
          const progress = this.byId("files-progress-bar");
          if (!progress.hasAttribute("value")) progress.value = 0;
          this.byId("files-progress-text").textContent = "状态读取中断，重新打开文件管理可继续查看。";
          throw new Error(e.message + " 请刷新列表核对结果；后台任务可能仍在运行。");
        }
        if (job.operation !== "reassign") { this.remember(null); throw new Error("更正任务信息已失效，请刷新文件列表。"); }
        const names = {queued:"等待处理", validating:"核对文件归属", writing:"更正文件归属", building:"更新血缘图", committing:"提交更正", complete:"更正完成"};
        const total = job.phase_total || 0, current = job.phase_processed || 0;
        this.byId("files-progress-text").textContent = (names[job.phase] || "处理中") + (total ? " · " + current + " / " + total : "") + (job.filename ? " · " + job.filename : "");
        const bar = this.byId("files-progress-bar");
        if (total) { bar.max = total; bar.value = current; } else bar.removeAttribute("value");
        if (job.status === "failed") {
          this.remember(null); this.invalidate(); bar.max = 1; bar.value = 0;
          this.byId("files-progress-text").textContent = "更正未完成，请查看下方提示。";
          this.message(job.error + (job.issues?.length ? "\n" + job.issues.slice(0,10).map(i => (i.filename ? i.filename + "：" : "") + i.message).join("\n") : ""), true);
          return;
        }
        if (job.status === "succeeded") {
          this.remember(null); this.selected.clear(); this.invalidate(); bar.max = 1; bar.value = 1;
          this.byId("files-progress-text").textContent = "更正完成，血缘图已更新。";
          const result = job.result;
          const message = "已将 " + result.changed + " 份文件更正为 " + result.target_line.toUpperCase() + "；无需更正 " + result.unchanged + " 份。原始 JSON 内容已保留。";
          this.message(message);
          try { await this.load(); await this.onChanged(); }
          catch { this.message(message + " 页面刷新失败，请刷新页面查看。", true); }
          return;
        }
        await new Promise(resolve => setTimeout(resolve, 700));
      }
    } finally { this.busy = false; this.controls(); }
  }
}
window.LineageDocumentManager = LineageDocumentManager;




