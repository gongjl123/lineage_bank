"use strict";

const business = { tag: "", root: "", summary: null, fieldId: "", availableCount: null, offset: 0, revision: 0, choiceRevision: 0 };

function resetBusinessSelection() {
  ++business.choiceRevision; ++business.revision;
  business.tag = ""; business.root = ""; business.summary = null;
  business.fieldId = ""; business.availableCount = null;
  $("business-dialog").close();
}

function renderBusinessBar() {
  const root = state.graph?.nodes.find(n => n.id === state.graph.root);
  const fieldId = root?.type === "column" ? root.id : "";
  if (fieldId !== business.fieldId) business.availableCount = null;
  business.fieldId = fieldId;
  const table = (root?.table_chinese_name ? root.table_chinese_name + " · " : "") + displayTableName(root);
  $("business-table").textContent = table || "请在左侧选表";
  $("business-table").title = table || "";
  $("business-field").textContent = fieldId ? displayNodeName(root) : "请在右侧选择字段";
  $("business-field").title = fieldId ? nodeTooltip(root) : "";
  $("business-open").disabled = !fieldId || business.availableCount === 0;
  $("business-open").textContent = business.tag || (business.availableCount === 0 ? "此字段未记录业务" : "选择字段业务");
  $("business-clear").hidden = !business.tag;
  $("business-query").disabled = !business.tag || !fieldId;
  $("business-query").textContent = business.tag ? "查询业务上游" : "查询上下游";
  document.querySelector('[data-trace="both"]').hidden = Boolean(business.tag);
  $("catalog-title").textContent = "选择数据表";
  $("business-summary").textContent = business.summary
    ? "当前字段：" + business.summary.branch_count + " 条加工路径 · " + business.summary.evidence_count + " 条来源依据"
    : fieldId ? "可查看明确业务及条件候选业务；标签出处和待解析内容见右侧“标签解析依据”。" : "操作顺序：选表 → 找字段 → 选业务 → 查询上下游";
  document.querySelector('[data-trace="upstream"]').textContent = business.tag ? "业务上游" : "查看上游";
  document.querySelector('[data-trace="downstream"]').textContent = business.tag ? "字段下游引用" : "查看下游";
}

async function loadBusinessTags(reset = false) {
  if (reset) business.offset = 0;
  const revision = ++business.revision, fieldId = $("business-dialog").dataset.fieldId;
  if (!fieldId) return;
  const data = await api("business-tags", { id: fieldId, q: $("business-search").value.trim(), offset: business.offset, limit: 12 });
  if (revision !== business.revision || fieldId !== $("business-dialog").dataset.fieldId) return;
  $("business-count").textContent = "该字段匹配 " + data.total + " 个业务";
  const list = $("business-list"); list.replaceChildren();
  for (const item of data.items) {
    const row = button("", () => chooseBusinessTag(item, fieldId), "business-item" + (business.root === fieldId && business.tag === item.name ? " selected" : ""));
    row.dataset.tag = item.name;
    row.append(el("strong", item.name), el("small", item.branch_count + " 条加工路径 · " + item.evidence_count + " 条来源依据" + (item.candidate_branch_count ? " · 含条件候选" : "")));
    list.append(row);
  }
  if (!data.total) list.append(el("p", "该字段没有匹配的业务。空白业务不列出，仍可直接查看字段上下游。", "muted"));
  $("business-page").textContent = (business.offset / 12 + 1) + " / " + Math.max(1, Math.ceil(data.total / 12));
  $("business-prev").disabled = business.offset === 0;
  $("business-next").disabled = business.offset + 12 >= data.total;
}

async function openFieldBusiness(fieldId = business.fieldId) {
  if (!fieldId) return notify("请先选择数据表，再在字段目录中选择字段。");
  $("business-dialog").dataset.fieldId = fieldId;
  const field = state.selected?.id === fieldId ? state.selected : state.graph?.nodes.find(node => node.id === fieldId);
  $("business-dialog-field").textContent = field ? displayBusinessQualified(field) : fieldId.replace("column:", "");
  $("business-search").value = "";
  $("business-list").replaceChildren(el("p", "正在读取当前字段业务…", "muted"));
  $("business-dialog").showModal();
  await loadBusinessTags(true);
}

async function chooseBusinessTag(item, fieldId = business.fieldId) {
  if (!fieldId) return;
  const previous = { tag: business.tag, root: business.root, summary: business.summary };
  const choice = ++business.choiceRevision;
  business.tag = item.name; business.root = fieldId; business.summary = item;
  $("business-dialog").close();
  try {
    await loadGraph(fieldId, "upstream");
  } catch (error) {
    if (choice === business.choiceRevision) { Object.assign(business, previous); renderBusinessBar(); }
    throw error;
  }
}

async function clearBusinessTag() {
  const root = business.root || state.graph?.root;
  resetBusinessSelection(); renderBusinessBar();
  if (root) await loadGraph(root);
}

function businessGraphNote() {
  const data = state.graph, active = Boolean(data?.business_tag);
  $("business-graph-note").hidden = !active;
  $("graph").classList.toggle("business-view", active);
  if (!active) return;
  const prefix = "当前字段业务：" + data.business_tag + "。";
  const upstreamScope = data.candidate_business
    ? "当前标签是条件候选：红色实线只表示候选标签直接命中的加工分支；更深上游是字段来源上下文，不继承该标签，也不代表业务条件已经成立。"
    : "红色实线只表示标签直接命中的加工分支；更深上游是字段来源上下文，不继承该标签。";
  const scope = data.direction === "downstream"
    ? "当前单独展示字段下游引用；这些节点不属于所选业务标签。"
    : upstreamScope + (data.direction === "both" ? "下游节点只是字段引用，不属于所选业务标签。" : "");
  $("business-graph-note").textContent = prefix + scope + "标签出处见右侧业务加工分支，点击连线可查看依据。";
  if (data.business_constants_auto_shown) $("business-graph-note").textContent += "当前业务的直接来源是常量，已自动显示常量节点。";
  if (!data.business_summary?.evidence_count) $("business-graph-note").textContent += "此业务只有加工说明，未记录来源连线。";
}

async function businessDetails(container, node, revision) {
  if (node.type !== "column") return;
  const section = el("section", null, "business-detail");
  section.append(el("h3", "选择字段业务"), el("p", "正在读取当前字段业务…", "muted")); container.append(section);
  const data = await api("business-tags", { id: node.id, limit: 6 });
  if (revision !== detailRevision) return;
  if (state.graph?.root === node.id) { business.availableCount = data.total; renderBusinessBar(); }
  const active = business.root === node.id && Boolean(business.tag);
  section.replaceChildren(el("h3", active ? "当前业务 · " + business.tag : "字段业务 · " + data.total));
  section.append(el("p", active
    ? "当前只按所选业务筛选进入该字段的直接加工分支；更深上游保留为来源上下文。普通下游引用需通过“字段下游引用”单独查看。条件候选不等于业务条件已经成立。"
    : data.total ? "业务来自加工记录及标签字段追溯；条件候选表示可能的业务值。选择后可查看对应路径入口。"
      : "当前字段未记录有效业务名称，可直接用上方按钮查看全部上下游。", "detail-kv"));
  if (active) {
    section.append(button("切换业务", () => openFieldBusiness(node.id)));
  } else {
    for (const item of data.items) {
      const row = button("", () => chooseBusinessTag(item, node.id), "field-business-option" + (business.root === node.id && business.tag === item.name ? " selected" : ""));
      row.dataset.tag = item.name;
      row.append(el("strong", item.name), el("small", item.branch_count + " 条加工路径 · " + item.evidence_count + " 条来源依据" + (item.candidate_branch_count ? " · 含条件候选" : "")));
      section.append(row);
    }
    if (data.total > 6) section.append(button("搜索全部 " + data.total + " 个业务", () => openFieldBusiness(node.id)));
  }
  const evidence = el("details", null, "business-branches");
  evidence.append(el("summary", "标签解析依据（含技术标签和待解析记录）"));
  section.append(evidence);
  let loaded = false;
  evidence.addEventListener("toggle", async () => {
    if (!evidence.open || loaded) return;
    loaded = true;
    const content = el("div"); evidence.append(content);
    const load = async (offset = 0) => {
      try {
        const result = await api("business-label-evidence", {id: node.id, offset, limit: 6});
        if (revision !== detailRevision) return;
        content.replaceChildren();
        for (const row of result.items) {
          const card = el("div", null, "business-branch");
          card.append(el("b", row.path + " · " + labelStatus(row.status)), el("small", row.document));
          card.append(el("small", "原始标签：" + (row.raw_value || "空白")));
          appendLabelEvidence(card, row);
          content.append(card);
        }
        if (!result.total) content.append(el("p", "未记录标签依据。", "muted"));
        if (result.total > 6) pager(content, offset, 6, result.total, load);
      } catch (error) { content.replaceChildren(el("p", error.message, "muted")); loaded = false; }
    };
    await load();
  });
  if (active) await businessBranchesPanel(section, node.id, business.tag, revision);
}

function labelStatus(status) {
  return ({resolved: "明确业务", candidate: "条件候选", partial: "部分解析，待核实", unresolved: "待解析", technical: "技术来源标签", empty: "空值标签"})[status] || status;
}

function appendLabelEvidence(container, row) {
  const method = ({direct:"直接记录", ancestor:"父路径继承", branch_group:"加工分支组传播", reference:"上游标签字段追溯", rule:"标签规则解析"})[row.method] || row.method;
  container.append(el("small", "标签方式：" + method + (row.source_path ? "，来自 " + row.source_path : "")));
  if (row.basis) container.append(el("small", row.basis));
  if (row.driver_table) container.append(el("small", "本段主表：" + row.driver_table));
  if (row.filter_condition) container.append(jsonDisclosure("本段取数条件", row.filter_condition));
  for (const value of row.labels || []) {
    container.append(el("b", value.name));
    if (value.conditions?.length) container.append(jsonDisclosure("适用条件（未执行业务数据）", value.conditions));
    if (value.evidence?.length) container.append(jsonDisclosure("标签上游出处", value.evidence));
  }
  for (const issue of row.issues || []) container.append(el("small", issue, "mapping-pending"));
}

async function businessBranchesPanel(container, nodeId, tag, revision) {
  if (!tag || business.root !== nodeId) return;
  const branchDetails = el("details", null, "business-branches");
  const branches = el("div", null, "business-branches-list");
  branchDetails.append(el("summary", "查看业务加工分支"), branches);
  container.append(branchDetails);
  let requestRevision = 0;
  const load = async (offset = 0) => {
    const request = ++requestRevision;
    const result = await api("business-branches", { id: nodeId, tag, offset, limit: 5 });
    if (revision !== detailRevision || request !== requestRevision || tag !== business.tag || business.root !== nodeId) return;
    branchDetails.querySelector("summary").textContent = "查看 " + result.total + " 条业务加工路径";
    branches.replaceChildren();
    for (const branch of result.items) {
      const card = el("div", null, "business-branch");
      card.append(el("b", tag), el("small", branch.path + " · " + (branch.source_count ? branch.source_count + " 条来源依据（可含常量）" : "有业务说明，未记录对应来源连线")), el("small", branch.document));
      if (branch.business_line) card.append(el("small", "来源条线：" + branch.business_line.toUpperCase()));
      if (branch.original_target) card.append(el("small", "原始目标：" + branch.original_target.replace("column:", "")));
      card.append(el("small", labelStatus(branch.label_status)));
      appendLabelEvidence(card, {method:branch.label_method, source_path:branch.business_tag_source_path,
        basis:branch.label_basis, labels:branch.label_values, issues:branch.label_issues});
      branches.append(card);
    }
    if (result.total > 5) pager(branches, offset, 5, result.total, load);
  };
  await load();
}

function setupBusiness() {
  $("business-open").onclick = run(() => openFieldBusiness());
  $("business-clear").onclick = run(clearBusinessTag);
  $("business-query").onclick = run(() => loadGraph(business.root, "upstream"));
  $("business-search").oninput = debounce(() => loadBusinessTags(true));
  $("business-prev").onclick = run(() => { business.offset -= 12; return loadBusinessTags(); });
  $("business-next").onclick = run(() => { business.offset += 12; return loadBusinessTags(); });
  renderBusinessBar();
}




