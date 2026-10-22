"use strict";

const $ = (id) => document.getElementById(id);
const state = { selected: null, graph: null, direction: "both", searchOffset: 0, issueOffset: 0, impactOffset: 0, stats: null, impact: null, demo: null, lineageView: "mapped" };
let searchRevision = 0, graphRevision = 0, detailRevision = 0, issueRevision = 0, noticeTimer;
const panelRequestRevision = new WeakMap();
const related = { root: null, data: null, direction: "all", offset: 0, revision: 0, pending: null };
let savedGraph = null;
let viewRevision = 0, statsRevision = 0, mappingRevision = 0, mappingOffset = 0;
const labels = { EMPTY_SOURCES: "未提供来源信息", EMPTY_SOURCES_USED: "来源待核查（有下游使用）", UNUSED_LOGIC: "未引用的逻辑", MISSING_LOGIC: "缺少路径逻辑", SELF_DEPENDENCY: "字段自依赖", EXTERNAL_SOURCE: "仅被引用", CROSS_DOCUMENT_TARGET: "跨文档分片", CYCLE: "循环依赖", MULTIPLE_DERIVATIONS: "多条派生", SUMMARY_MISMATCH: "摘要不一致" };
const reasons = { depth_limit: "已达追踪深度，边界仍有关系", node_limit: "已达节点上限", edge_limit: "已达连线上限" };
const pretty = (data) => JSON.stringify(data, null, 2);
const run = (fn) => (...args) => Promise.resolve().then(() => fn(...args)).catch((error) => notify(error.message, true));
const displayQualified = (node) => node?.display_qualified_name || node?.qualified_name || "";
const displayNodeName = (node) => (node?.chinese_name ? node.chinese_name + " · " : "") + (node?.display_name || node?.name || "");
const displayBusinessQualified = (node) => (node?.chinese_name ? node.chinese_name + " · " : "") + displayQualified(node);
const displayNamespace = (node) => node?.display_namespace || node?.namespace || "";
const displayTableName = (node) => node?.display_table || (node?.type === "table" ? displayQualified(node) : node?.parent_id?.replace("table:", "")) || "";
const searchableNodeName = (node) => (displayQualified(node) + " " + (node?.name_candidates || []).join(" ") + " " + (node?.table_chinese_name || "") + " " + (node?.original_names || [node?.qualified_name || ""]).join(" " )).toUpperCase();
function nodeTooltip(node) {
  const displayed = displayBusinessQualified(node);
  return displayed + (node?.mapping_pending ? "\n待匹配：" + node.mapping_message : "") +
    (node?.mapping_changed ? "\n原始成员：" + (node.original_names || [node.original_qualified_name]).join("、") : "");
}
function displayEntityId(id, nodes = state.graph?.nodes || []) {
  const node = nodes.find((item) => item.id === id);
  return node ? displayBusinessQualified(node) : String(id).replace(/^(table|column):/, "");
}

function el(tag, text, className) {
  const node = document.createElement(tag);
  if (text !== undefined && text !== null) node.textContent = text;
  if (className) node.className = className;
  return node;
}
function button(text, handler, className) {
  const node = el("button", text, className);
  node.addEventListener("click", run(handler));
  return node;
}
function notify(message, error = false, persistent = false) {
  clearTimeout(noticeTimer);
  $("notice").textContent = message;
  $("notice").className = error ? "error" : "";
  $("notice").hidden = false;
  if (!persistent) noticeTimer = setTimeout(() => { $("notice").hidden = true; }, error ? 9000 : 4000);
}
async function api(path, params = {}, body) {
  const query = new URLSearchParams(body === undefined ? { view: state.lineageView, ...params } : params).toString();
  const response = await fetch("/api/" + path + (query ? "?" + query : ""), body === undefined ? {} : { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) });
  const data = await response.json();
  if (!response.ok) {
    const error = new Error(typeof data.detail === "string" ? data.detail : data.issues?.[0]?.message || "请求失败（" + response.status + "），请检查参数或服务状态。");
    error.data = data;
    throw error;
  }
  return data;
}
function debounce(fn, delay = 220) {
  let timer;
  return (...args) => { clearTimeout(timer); timer = setTimeout(run(() => fn(...args)), delay); };
}
function pager(container, offset, limit, total, change) {
  const row = el("div", null, "pager");
  const prev = button("上一页", () => change(Math.max(0, offset - limit)));
  const next = button("下一页", () => change(offset + limit));
  prev.disabled = offset === 0; next.disabled = offset + limit >= total;
  row.append(prev, el("span", (Math.floor(offset / limit) + 1) + " / " + Math.max(1, Math.ceil(total / limit))), next);
  container.append(row);
}

async function refreshStats() {
  const revision = ++statsRevision;
  const [stats, lines] = await Promise.all([api("stats"), api("lines")]);
  if (revision !== statsRevision) return;
  state.stats = stats;
  const previousLine = $("line-filter").value;
  $("line-filter").replaceChildren(new Option("全部条线（目标表）", ""), ...lines.items.map(item => new Option(item.name + " · " + item.tables + " 张目标表", item.id)));
  $("line-filter").value = previousLine;
  if (!$("import-line")) {
    const label = el("label", "导入所属条线 ", "import-line-label");
    const select = el("select"); select.id = "import-line"; select.setAttribute("aria-label", "导入所属条线");
    select.append(new Option("请选择条线", ""), ...lines.items.map(item => new Option(item.name, item.id)));
    label.append(select); document.querySelector(".import-zone").before(label);
  }
  for (const key of ["documents", "tables", "columns", "relations"]) $("stat-" + key).textContent = state.stats[key].toLocaleString();
  const definedTables = Math.max(0, state.stats.tables - state.stats.external_tables);
  const definedColumns = Math.max(0, state.stats.columns - state.stats.external_columns);
  const hasData = state.stats.documents > 0;
  const mapping = state.stats.table_mapping;
  const connectivity = state.stats.mapping_connectivity;
  const mappingStatus = connectivity ? "；归组 " + connectivity.grouped_tables + " 组；待匹配字段 " + connectivity.pending_fields : "";
  $("metrics-status").textContent = (state.lineageView === "mapped" ? "业务映射视图" : "原始技术视图") + " · " + (hasData ? "当前已导入的 JSON" : "暂无数据，请导入 JSON") + mappingStatus;
  $("mapping-open").hidden = true;
  $("mapping-open").textContent = "映射待匹配" + (connectivity ? " · " + connectivity.pending_fields : "");
  $("data-status").textContent = hasData ? "已导入数据" : "暂无数据";
  $("data-status").parentElement.classList.toggle("is-empty", !hasData);
  $("stats-source").textContent = "以下为当前视图的整体统计，不随左侧筛选或画布选择变化。" + (state.stats.original_counts ? "原始技术数据仍保留 " + state.stats.original_counts.tables + " 张表、" + state.stats.original_counts.columns + " 个字段。" : "");
  $("stats-documents-detail").textContent = state.stats.documents.toLocaleString() + " 份已成功提交、参与血缘分析的 JSON，不含仅校验或导入失败的文件。";
  $("stats-tables-detail").textContent = state.stats.tables.toLocaleString() + " 张 = 已定义目标表 " + definedTables.toLocaleString() + " 张 + 仅被引用表 " + state.stats.external_tables.toLocaleString() + " 张。按完整表名去重，不等于各条线目标表数量之和。";
  $("stats-columns-detail").textContent = state.stats.columns.toLocaleString() + " 个 = 已定义字段 " + definedColumns.toLocaleString() + " 个 + 仅被引用字段 " + state.stats.external_columns.toLocaleString() + " 个。" + (state.lineageView === "mapped" ? "B 已有同名字段时归并；待匹配字段按原始身份单独保留。" : "按原始表和字段名去重。");
  $("stats-relations-detail").textContent = state.stats.relations.toLocaleString() + " 条已解析的直接来源依据。同一条画布连线可汇总多条依据，因此这个数字不等于画布连线数量。";
  $("stats-open").disabled = false;
  $("metric-documents").title = "当前数据库中已提交并参与构图的 JSON 文档数量。";
  $("metric-tables").title = "顶部数字是全部表实体：唯一已定义目标表 + 仅被引用的外部表；不等于各条线目标表数量之和。";
  $("metric-columns").title = "顶部数字是全部字段实体：已定义字段 + 仅被引用的外部字段。";
  $("metric-relations").title = "所有 sources 数组元素形成的来源依据数量；同一来源在不同路径或文件中分别保留。";
  $("quality-open").title = "当前有 " + state.stats.issues.toLocaleString() + " 条质量提示，点击查看明细。";
  $("quality-count").textContent = state.stats.issues.toLocaleString();
  $("quality-count").hidden = state.stats.issues === 0;
  const oldNamespace = $("namespace").value;
  $("namespace").replaceChildren(new Option("全部命名空间", ""), ...state.stats.namespaces.map((n) => new Option(n, n)), new Option("常量", "CONST"));
  $("namespace").value = oldNamespace;
  $("issue-code").replaceChildren(new Option("全部问题类型", ""), ...Object.entries(state.stats.issue_counts).map(([code, count]) => new Option((labels[code] || code) + " · " + count, code)));
}
async function configureDemo() {
  const health = await api("health");
  state.demo = health;
  // Data quality remains available to diagnostics/API, but is not shown on the business-facing page.
  $("quality-open").hidden = true;
  if (!health.demo_mode) return;
  const banner = $("demo-scope");
  banner.textContent = health.demo_scoped ? health.demo_target + " 及上下游" : "默认定位 " + health.demo_target;
  banner.hidden = false;
  if (health.demo_writable) {
    $("files-open").title = "查看和维护当前演示数据库中的导入文件";
    $("import-open").title = "导入后重新计算血缘；无关数据也会正常显示在左侧";
  } else {
    $("files-open").title = "演示模式下可查看范围内文件，修改操作为只读";
    $("import-open").title = "演示模式下保留入口，但提交导入会被只读策略拦截";
  }
}
async function search(reset = false) {
  if (reset) state.searchOffset = 0;
  const revision = ++searchRevision;
  const data = await api("nodes", { q: $("search").value, kind: $("kind").value, namespace: $("namespace").value, status: $("status-filter").value, line: $("line-filter").value, offset: state.searchOffset, limit: 40 });
  if (revision !== searchRevision) return;
  $("search-count").textContent = data.total.toLocaleString();
  $("search-count").title = $("status-filter").value === "defined" ? "当前筛选范围内的目标定义表数量；全部条线时为唯一目标表数量。" : $("status-filter").value === "external" ? "当前筛选范围内仅被引用、没有目标定义的表数量。" : "当前筛选范围内全部表实体数量，包含仅被引用表。";
  $("node-list").replaceChildren();
  for (const node of data.items) {
    const item = button("", () => focusNode(node.id), "catalog-node" + (state.selected?.id === node.id ? " selected" : ""));
    item.dataset.nodeId = node.id; item.title = nodeTooltip(node);
    item.append(el("div", displayNodeName(node), "node-title"));
    const caption = el("div", null, "node-caption");
    caption.append(el("span", (node.defined_lines?.map(x => x.toUpperCase()).join("/") || "仅引用") + " · " + displayNamespace(node)), el("span", node.type === "table" ? node.field_count + " 字段" : node.defined ? "已定义" : "仅引用"));
    item.append(caption);
    if (node.mapping_grouped) item.append(el("small", node.mapping_member_count + " 个原始成员", "mapping-caption"));
    if (node.mapping_pending || node.pending_field_count) item.append(el("small", node.mapping_pending ? "待匹配" : node.pending_field_count + " 个字段待匹配", "mapping-pending"));
    $("node-list").append(item);
  }
  if (!data.total) $("node-list").append(el("p", "没有匹配的数据对象，请调整查询条件。", "muted"));
  $("search-page").textContent = (state.searchOffset / 40 + 1) + " / " + Math.max(1, Math.ceil(data.total / 40));
  $("search-prev").disabled = state.searchOffset === 0;
  $("search-next").disabled = state.searchOffset + 40 >= data.total;
}

async function focusNode(id, direction = "both") {
  state.direction = direction;
  await loadGraph(id, direction);
}
async function loadGraph(id, direction = state.direction) {
  if (!id) return notify("请先选择数据对象。");
  const revision = ++graphRevision;
  const tag = business.root === id ? business.tag : "";
  const params = { id, direction, depth: $("depth").value, max_nodes: 300, max_edges: 1000, include_constants: $("constants").checked, ...(tag ? { tag } : {}) };
  let data = await api(tag ? "business-lineage" : "graph", params);
  if (revision !== graphRevision) return;
  const hasDirectBusinessEdge = data.edges.some(edge => edge.business_match_count > 0);
  if (tag && direction !== "downstream" && !params.include_constants && data.business_summary?.evidence_count && !hasDirectBusinessEdge) {
    const withConstants = await api("business-lineage", { ...params, include_constants: true });
    if (revision !== graphRevision) return;
    const constantIds = new Set(withConstants.nodes.filter(node => node.type === "constant").map(node => node.id));
    const hasDirectConstant = withConstants.edges.some(edge => edge.target === id && edge.business_match_count > 0 && constantIds.has(edge.source));
    if (hasDirectConstant) {
      data = withConstants;
      data.business_constants_auto_shown = true;
      $("constants").checked = true;
    }
  }
  if (revision !== graphRevision) return;
  if (business.root && business.root !== id) resetBusinessSelection();
  savedGraph = null;
  setPathMode(false);
  state.graph = data; state.direction = direction;
  renderBusinessBar();
  const root = data.nodes.find((n) => n.id === data.root);
  $("graph-title").textContent = displayBusinessQualified(root);
  $("graph-title").title = nodeTooltip(root);
  $("graph-empty").hidden = true;
  document.querySelectorAll("[data-trace]").forEach((b) => b.classList.toggle("active", b.dataset.trace === direction));
  drawGraph(true);
  refreshRelated(root);
  await selectNode(root);
}
async function expandNode(id) {
  if (state.graph?.path_mode) return notify("请先返回关系图，再展开相邻关系。");
  if (!state.graph) return focusNode(id);
  if (state.graph.business_tag) {
    const depth = Math.min(100, Number($("depth").value) + 1);
    if (depth === Number($("depth").value)) return notify("已达最大追踪层级 100，可选择其他字段继续查询。");
    if (![...$("depth").options].some(option => Number(option.value) === depth)) $("depth").add(new Option(String(depth), String(depth)));
    $("depth").value = String(depth);
    await loadGraph(state.graph.root);
    return notify("业务查询已增加一层，继续沿当前查询方向展开。");
  }
  const revision = ++graphRevision, oldGraph = state.graph;
  const data = await api("graph", { id, direction: "both", depth: 1, max_nodes: 300, include_constants: $("constants").checked });
  if (revision !== graphRevision || state.graph !== oldGraph) return;
  const nodes = new Map(oldGraph.nodes.map((n) => [n.id, n]));
  const level = nodes.get(id)?.distance || 0;
  let limited = false;
  for (const node of data.nodes) {
    if (nodes.has(node.id)) continue;
    if (nodes.size >= 300) { limited = true; continue; }
    nodes.set(node.id, { ...node, distance: level + node.distance });
  }
  const edges = new Map(oldGraph.edges.map((edge) => [edge.id, edge]));
  for (const edge of data.edges) if (nodes.has(edge.source) && nodes.has(edge.target)) edges.set(edge.id, edge);
  const reasonSet = new Set([...oldGraph.reasons, ...data.reasons]);
  if (limited) reasonSet.add("node_limit");
  if (edges.size > 1000) reasonSet.add("edge_limit");
  state.graph = { ...oldGraph, nodes: [...nodes.values()], edges: [...edges.values()].slice(0, 1000), total_edges: edges.size, reasons: [...reasonSet], truncated: reasonSet.size > 0, expanded: true };
  drawGraph(true);
  notify("已显示相邻一层关系。");
}

let view = { x: 0, y: 0, w: 1000, h: 600 }, bounds = { x: 0, y: 0, w: 1000, h: 600 };
function svg(tag, attributes = {}, text) {
  const node = document.createElementNS("http://www.w3.org/2000/svg", tag);
  for (const [key, value] of Object.entries(attributes)) node.setAttribute(key, value);
  if (text !== undefined) node.textContent = text;
  return node;
}
const short = (text, limit = 28) => text.length > limit ? text.slice(0, limit - 1) + "…" : text;
function drawGraph(fit = false) {
  if (!state.graph) return;
  const data = state.graph, content = $("graph-content"); content.replaceChildren();
  businessGraphNote();
  const columns = new Map();
  for (const node of data.nodes) { const key = node.distance || 0; if (!columns.has(key)) columns.set(key, []); columns.get(key).push(node); }
  const positions = new Map();
  const graphNodes = new Map(data.nodes.map((node) => [node.id, node]));
  const levels = [...columns.keys()].sort((a, b) => a - b);
  const height = Math.max(220, ...[...columns.values()].map((items) => items.length * 98));
  levels.forEach((level, col) => {
    const items = columns.get(level).sort((a, b) => a.id.localeCompare(b.id));
    items.forEach((node, row) => positions.set(node.id, { x: col * (data.path_mode ? 270 : 335) + 30, y: (height - items.length * 98) / 2 + row * 98 + 25 }));
  });
  bounds = { x: -10, y: -25, w: Math.max(380, levels.length * (data.path_mode ? 270 : 335) + (data.path_mode ? 40 : 0)), h: height + 110 };
  for (const edge of data.edges) {
    const source = positions.get(edge.source), target = positions.get(edge.target);
    if (!source || !target) continue;
    const sameLevel = edge.source !== edge.target && graphNodes.get(edge.source)?.distance === graphNodes.get(edge.target)?.distance;
    let path;
    if (edge.source === edge.target) {
      path = "M " + (source.x + 160) + " " + source.y + " C " + (source.x + 240) + " " + (source.y - 40) + " " + (source.x + 270) + " " + (source.y + 30) + " " + (source.x + 230) + " " + (source.y + 35);
    } else {
      const forward = target.x > source.x, same = target.x === source.x;
      const x1 = source.x + (forward || same ? 230 : 0), x2 = target.x + (forward ? 0 : 230);
      const y1 = source.y + 34, y2 = target.y + 34;
      const curve = same ? 80 : Math.max(45, Math.abs(x2 - x1) / 2);
      path = "M " + x1 + " " + y1 + " C " + (x1 + (forward || same ? curve : -curve)) + " " + y1 + " " + (x2 + (forward ? -curve : curve)) + " " + y2 + " " + x2 + " " + y2;
    }
    const matched = edge.business_match_count || 0;
    const businessTitle = matched
      ? "当前业务直接命中的加工关系，点击查看关系明细"
      : "普通字段血缘上下文，未继承当前业务标签，点击查看关系明细";
    const edgeTitle = data.business_tag ? businessTitle : "点击查看关系明细";
    const businessClass = data.business_tag ? matched ? " business-match business-direct" : " business-context" : "";
    const group = svg("g", { class: "edge-group" + (sameLevel ? " same-level-edge" : "") + businessClass, tabindex: 0, role: "button", "data-source": edge.source, "data-target": edge.target, "aria-label": (sameLevel ? "同层补充关系，" : "") + edgeTitle });
    group.append(svg("title", {}, edgeTitle), svg("path", { d: path, class: "graph-edge" }), svg("path", { d: path, class: "edge-hit" }));
    group.addEventListener("click", run(() => edgeDetails(edge)));
    group.addEventListener("keydown", run((event) => { if (event.key === "Enter") return edgeDetails(edge); }));
    content.append(group);
  }
  for (const node of data.nodes) {
    const p = positions.get(node.id), kind = node.type === "constant" ? "constant" : node.defined ? "defined" : "external";
    const group = svg("g", { class: "graph-node " + kind + (node.mapping_pending ? " mapping-pending-node" : "") + (state.selected?.id === node.id ? " selected" : "") + (node.business_match ? " business-match" : ""), transform: "translate(" + p.x + "," + p.y + ")", tabindex: 0, role: "button", "aria-label": displayBusinessQualified(node), "data-node-id": node.id });
    group.append(svg("title", {}, nodeTooltip(node) + (node.source_review_required ? " · 来源待核查（有下游使用）" : node.unresolved ? " · 未提供来源信息" : "")), svg("rect", { width: 230, height: 68, rx: 7 }), svg("circle", { cx: 14, cy: 21, r: 3, class: "node-dot" }));
    group.append(svg("text", { x: 25, y: 25, class: "node-label" }, short(displayNodeName(node), data.path_mode ? 24 : 28)), svg("text", { x: 14, y: 49, class: "node-meta" }, short(node.type === "column" ? displayTableName(node) : (node.defined_lines?.map(x => x.toUpperCase()).join("/") || "仅引用") + " · " + displayNamespace(node), 26)));
    group.append(svg("text", { x: 217, y: 49, class: "node-meta", "text-anchor": "end" }, node.mapping_pending ? "待匹配" : !node.defined ? "仅引用" : node.type === "table" ? node.field_count + " 字段" : node.self_reference_count ? "自身引用" : node.multi_node_cycle ? "循环依赖" : ""));
    group.addEventListener("click", run(() => selectNode(node)));
    group.addEventListener("dblclick", run(() => expandNode(node.id)));
    group.addEventListener("keydown", run((event) => { if (event.key === "Enter") return selectNode(node); if (event.key === " ") { event.preventDefault(); return expandNode(node.id); } }));
    content.append(group);
  }
  const status = $("graph-status");
  status.classList.toggle("warning", data.truncated);
  status.textContent = "当前显示 " + data.nodes.length + " 个数据对象、" + data.edges.length + " 条关系。" + (data.expanded ? "已补充相邻一层关系；" : "追踪层级为 " + data.depth + "；") + (data.truncated ? data.reasons.map((r) => reasons[r] || r).join("；") + "。" : "当前范围已完整显示。") + (data.approximation ? "表内字段依赖请在右侧详情中查看。" : "关系范围以 JSON 中明确记录的字段来源为准。");
  if (data.path_mode) status.textContent = "当前只显示一条最短表级路径，共 " + data.edges.length + " 层。箭头由来源指向目标；可能还有其他路径。表级路径不代表同一个字段连续传递，可点击连线核对字段依据。";
  if (state.lineageView === "mapped") status.textContent += " 当前按表映射及 B 已有同名字段衔接；待匹配字段保留原血缘。";
  if (fit) fitView();
}
function applyView() { $("graph").setAttribute("viewBox", [view.x, view.y, view.w, view.h].join(" ")); }

function setPathMode(active) {
  $("path-banner").hidden = !active;
  $("graph").classList.toggle("path-view", active);
  for (const control of document.querySelectorAll("[data-trace],#depth,#constants")) control.disabled = active;
}
function relatedButton() {
  const label = "整表全部关联";
  $("related-open").textContent = label + (related.data ? " · " + (related.data.truncated ? "≥" : "") + related.data.total : "");
  $("related-open").title = "查看当前表的全部上下游关联表；若当前选择的是字段，则按字段所属表统计";
  $("related-open").disabled = !related.root;
}
function refreshRelated(node, force = false) {
  const id = node.type === "table" ? node.id : node.parent_id;
  if (!force && id === related.root && (related.data || related.pending)) { relatedButton(); return related.pending; }
  const revision = ++related.revision;
  related.root = id; related.data = null; related.pending = null;
  relatedButton();
  if (!id) return;
  related.pending = api("associated-tables", { id }).then((data) => {
    if (revision !== related.revision) return;
    related.data = data;
    relatedButton();
    if ($("related-dialog").open) renderRelated();
  }).catch((error) => {
    if (revision === related.revision) {
      $("related-open").textContent = "关联表清单 · 点击重试";
      notify("关联表清单加载失败：" + error.message, true);
    }
  }).finally(() => { if (revision === related.revision) related.pending = null; });
  return related.pending;
}
async function openRelated() {
  const root = state.graph?.nodes.find((node) => node.id === state.graph.root);
  if (!root) return;
  await refreshRelated(root);
  if (!related.data) return;
  related.direction = "all"; related.offset = 0;
  $("related-search").value = ""; $("related-distance").value = "all";
  renderRelated();
  $("related-dialog").showModal();
}
function renderRelated() {
  const data = related.data;
  if (!data) return;
  const selectedRoot = state.graph?.nodes.find((node) => node.id === state.graph.root);
  const tableScope = selectedRoot?.type === "table";
  $("related-title").textContent = "整表全部关联";
  $("related-root").textContent = (tableScope ? "当前表：" : "当前字段所属表：") + (displayTableName(selectedRoot) || data.root.replace("table:", ""));
  const summary = $("related-summary"); summary.replaceChildren();
  const total = el("div"); total.append(el("span", "全部关联表"), el("strong", (data.truncated ? "≥ " : "") + data.total), el("small", "按表去重，不含当前表")); summary.append(total);
  for (const direction of ["upstream", "downstream"]) {
    const item = data.summary[direction], cell = el("div");
    cell.append(el("span", direction === "upstream" ? "上游关联表" : "下游关联表"), el("strong", (item.truncated ? "≥ " : "") + item.total), el("small", "直接 " + item.direct + " · 间接 " + item.indirect + " · 最远 " + item.max_distance + " 层")); summary.append(cell);
  }
  $("related-scope").textContent = (tableScope
    ? "这是所有已导入条线中当前表的关联范围。"
    : "当前选择的是字段或业务查询，以下仍按该字段所属整张表统计。")
    + "不受左侧条线筛选、业务标签或画布追踪层级限制。"
    + (data.truncated ? "查询已达节点上限，以下为部分结果。" : "已遍历当前已导入数据的全部可达表。")
    + "层级按最短路径计算；同一张表可能同时属于上游和下游。下游代表可能涉及的范围，具体字段需查看字段血缘。";
  if (state.lineageView === "mapped") $("related-scope").textContent += "当前按业务表归组统计，路径可通过表映射及已有同名字段衔接。";
  $("related-scope").classList.toggle("warning", data.truncated);
  document.querySelectorAll("[data-related-direction]").forEach((button) => {
    const active = button.dataset.relatedDirection === related.direction;
    button.classList.toggle("active", active); button.setAttribute("aria-pressed", String(active));
    const count = button.dataset.relatedDirection === "all" ? data.total : data.summary[button.dataset.relatedDirection].total;
    button.textContent = ({ all: "全部", upstream: "上游关联表", downstream: "下游关联表" }[button.dataset.relatedDirection]) + " · " + count;
  });
  const needle = $("related-search").value.trim().toUpperCase(), distanceFilter = $("related-distance").value;
  const directions = related.direction === "all" ? ["upstream", "downstream"] : [related.direction];
  const matches = (distance) => distance !== null && (distanceFilter === "all" || (distanceFilter === "direct" ? distance === 1 : distance > 1));
  const items = data.items.filter((item) => searchableNodeName(item).includes(needle) && directions.some((direction) => matches(item[direction + "_distance"])));
  items.sort((a, b) => Math.min(...directions.map((dir) => a[dir + "_distance"] ?? Infinity)) - Math.min(...directions.map((dir) => b[dir + "_distance"] ?? Infinity)) || a.id.localeCompare(b.id));
  related.offset = Math.min(related.offset, Math.max(0, Math.floor((items.length - 1) / 20) * 20));
  $("related-count").textContent = "筛选结果 " + items.length + " 张表 · 点击“查看路径”可单独查看与当前表之间的一条最短路径。";
  const list = $("related-list"); list.replaceChildren();
  for (const item of items.slice(related.offset, related.offset + 20)) {
    const row = el("tr"), name = el("td", displayBusinessQualified(item)), relations = el("td"), status = el("td", item.defined ? "有目标定义" : "仅被引用"), actions = el("td");
    name.title = nodeTooltip(item);
    if (item.defined_lines?.length) name.append(el("small", " · " + item.defined_lines.map(x => x.toUpperCase()).join(" / ")));
    const visibleDirections = directions.filter((direction) => matches(item[direction + "_distance"]));
    for (const direction of visibleDirections) {
      const distance = item[direction + "_distance"], label = direction === "upstream" ? "上游" : "下游";
      relations.append(el("div", (distance === 1 ? "直接" : "间接") + label + " · " + distance + " 层"));
      actions.append(button(visibleDirections.length > 1 ? "查看" + label + "路径" : "查看路径", () => showTablePath(item.id, direction)));
    }
    row.append(name, relations, status, actions); list.append(row);
  }
  $("related-empty").hidden = items.length > 0;
  $("related-page").textContent = (Math.floor(related.offset / 20) + 1) + " / " + Math.max(1, Math.ceil(items.length / 20));
  $("related-prev").disabled = related.offset === 0;
  $("related-next").disabled = related.offset + 20 >= items.length;
}
function currentRelations(container) {
  const data = state.graph;
  if (!data?.business_tag) return;
  const nodes = new Map(data.nodes.map((node) => [node.id, node]));
  const edges = [...data.edges].sort((a, b) => (Math.max(Math.abs(nodes.get(a.source)?.distance || 0), Math.abs(nodes.get(a.target)?.distance || 0)) - Math.max(Math.abs(nodes.get(b.source)?.distance || 0), Math.abs(nodes.get(b.target)?.distance || 0))) || a.id.localeCompare(b.id));
  const section = el("details", null, "current-relations"), list = el("div");
  section.append(el("summary", "当前查询关系 · " + edges.length + " 条"), el("p", "红线只表示标签直接命中的加工分支；更深上游和下游是字段血缘上下文，不继承当前标签。", "detail-kv"), list);
  container.append(section);
  const render = (offset = 0) => {
    list.replaceChildren();
    for (const edge of edges.slice(offset, offset + 12)) {
    const source = nodes.get(edge.source), target = nodes.get(edge.target);
    if (!source || !target) continue;
    const sourceName = displayBusinessQualified(source), targetName = displayBusinessQualified(target);
    const distance = Math.max(Math.abs(source.distance || 0), Math.abs(target.distance || 0));
    const direction = edge.target === data.root ? "上游" : edge.source === data.root ? "下游" : source.distance < 0 ? "继续上游" : target.distance > 0 ? "继续下游" : "同层关系";
    const row = el("article", null, "evidence-card");
    row.append(el("small", sourceName + " → " + targetName), el("b", direction + " · " + distance + " 层"));
    const matched = edge.business_match_count || 0;
    row.append(el("small", matched ? "直接业务关系 " + matched + " / " + edge.evidence_count : "普通字段血缘上下文"));
    row.append(button("查看依据", () => edgeDetails(edge))); list.append(row);
    }
    if (edges.length > 12) pager(list, offset, 12, edges.length, render);
  };
  render();
}
async function showTablePath(target, direction) {
  const revision = ++graphRevision;
  const data = await api("table-path", { id: related.root, target, direction });
  if (revision !== graphRevision) return;
  if (!savedGraph) savedGraph = { graph: state.graph, direction: state.direction, selected: state.selected };
  state.graph = data;
  $("related-dialog").close();
  setPathMode(true);
  const pathNodes = new Map(data.nodes.map((node) => [node.id, node]));
  $("graph-title").textContent = displayBusinessQualified(pathNodes.get(data.root));
  $("graph-title").title = nodeTooltip(pathNodes.get(data.root));
  $("path-caption").textContent = "一条最短路径 · " + data.path.map((id) => displayBusinessQualified(pathNodes.get(id)) || id.replace("table:", "")).join(" → ");
  drawGraph(true);
  relatedButton();
  await selectNode(data.nodes.find((node) => node.id === data.root));
}
async function returnFromPath() {
  if (!savedGraph) return;
  ++graphRevision;
  const previous = savedGraph; savedGraph = null;
  state.graph = previous.graph; state.direction = previous.direction;
  setPathMode(false);
  const root = state.graph.nodes.find((node) => node.id === state.graph.root);
  $("graph-title").textContent = displayBusinessQualified(root); $("graph-title").title = nodeTooltip(root);
  document.querySelectorAll("[data-trace]").forEach((button) => button.classList.toggle("active", button.dataset.trace === state.direction));
  drawGraph(true); relatedButton();
  await selectNode(previous.selected || root);
}
function fitView() {
  const box = $("graph").getBoundingClientRect(), ratio = box.width / Math.max(1, box.height);
  const w = Math.max(bounds.w, bounds.h * ratio), h = w / ratio;
  view = { x: bounds.x - (w - bounds.w) / 2, y: bounds.y - (h - bounds.h) / 2, w, h }; applyView();
}
function zoom(factor, cx = .5, cy = .5) {
  const w = Math.max(200, Math.min(50000, view.w * factor)), h = view.h * w / view.w;
  view = { x: view.x + (view.w - w) * cx, y: view.y + (view.h - h) * cy, w, h }; applyView();
}
$("graph").addEventListener("wheel", (event) => { event.preventDefault(); const box = $("graph").getBoundingClientRect(); zoom(event.deltaY > 0 ? 1.12 : .89, (event.clientX - box.left) / box.width, (event.clientY - box.top) / box.height); }, { passive: false });
let dragging;
$("graph").addEventListener("pointerdown", (event) => { if (event.target.closest(".graph-node,.edge-group")) return; dragging = { x: event.clientX, y: event.clientY, view: { ...view } }; $("graph").setPointerCapture(event.pointerId); });
$("graph").addEventListener("pointermove", (event) => { if (!dragging) return; const box = $("graph").getBoundingClientRect(); view.x = dragging.view.x - (event.clientX - dragging.x) * view.w / box.width; view.y = dragging.view.y - (event.clientY - dragging.y) * view.h / box.height; applyView(); });
$("graph").addEventListener("pointerup", () => { dragging = null; });
$("graph").addEventListener("pointercancel", () => { dragging = null; });

async function selectNode(node) {
  state.selected = node;
  const revision = ++detailRevision;
  const businessMode = Boolean(business.tag && business.root === node.id);
  document.querySelectorAll("[data-node-id]").forEach((item) => item.classList.toggle("selected", item.dataset.nodeId === node.id));
  const box = $("details-content"); box.replaceChildren(el("h3", displayBusinessQualified(node)));
  const chips = el("div", null, "chips");
  chips.append(el("span", { table: "数据表", column: "字段", constant: "常量" }[node.type], "chip"), el("span", node.defined ? "已定义" : "仅引用", "chip " + (node.defined ? "teal" : "amber")));
  if (node.unresolved) chips.append(el("span", node.source_review_required ? "来源待核查（有下游使用）" : node.type === "table" ? "部分字段未提供来源信息" : "未提供来源信息", node.source_review_required ? "chip amber" : "chip"));
  if (node.self_reference_count) chips.append(el("span", (node.type === "table" ? "表内字段依赖 " : "字段自身引用 ") + node.self_reference_count + " 条", "chip"));
  if (node.multi_node_cycle) chips.append(el("span", node.type === "table" ? "涉及多表依赖环" : "涉及多字段依赖环", "chip amber"));
  if (node.mapping_changed) chips.append(el("span", "湖表映射", "chip teal"));
  if (node.mapping_grouped) chips.append(el("span", node.mapping_member_count + " 个原始成员", "chip teal"));
  if (node.mapping_pending || node.pending_field_count) chips.append(el("span", node.mapping_pending ? "待匹配" : node.pending_field_count + " 个字段待匹配", "chip amber"));
  box.append(chips, el("p", "全部直接上游：" + node.upstream_count + "　全部直接下游：" + node.downstream_count, "detail-kv"));
  if (node.name_status === "unmatched") box.append(el("p", "当前中英文对照未覆盖此对象，暂显示英文标识。", "detail-kv"));
  if (node.name_status === "conflict") box.append(el("p", "原始成员的中文名称不一致，待核对：" + node.name_candidates.join(" / "), "mapping-pending-note"));
  if (node.name_dictionary_basis?.length) box.append(jsonDisclosure("中文名称来源", node.name_dictionary_basis));
  if (node.mapping_changed) {
    box.append(el("p", "按 " + node.mapping_sheets.join(" / ") + " 的 A → B 关系归组，汇集原始成员的上下游。来源依据保留原始名称、条线和加工路径。", "mapping-origin"));
    const members = el("details", null, "mapping-members"); members.append(el("summary", "查看映射成员"));
    for (const member of node.mapping_members || []) {
      const row = el("div", null, "mapping-member");
      row.append(el("small", member.qualified_name), el("small", "定义：" + (member.defined_lines.join(" / ") || "未采集") + "；引用：" + (member.referenced_lines.join(" / ") || "无")));
      members.append(row);
    }
    box.append(members);
  }
  if (node.mapping_pending) box.append(el("p", "原始字段：" + node.original_qualified_name + "；候选业务字段：" + node.mapping_candidate + "。" + node.mapping_message + "；保留原来的连接，暂不合并此字段。", "mapping-pending-note"));
  else if (node.pending_field_count) box.append(el("p", "本表有 " + node.pending_field_count + " 个字段待匹配。表级上下游已汇集；这些字段尚不能借助映射连接 B 的字段路径。", "mapping-pending-note"));
  box.append(el("p", "目标定义条线：" + (node.defined_lines?.map(x => x.toUpperCase()).join(" / ") || "当前未采集目标定义") + "；引用条线：" + (node.referenced_lines?.map(x => x.toUpperCase()).join(" / ") || "无"), "detail-kv"));
  if (businessMode) box.append(el("p", "当前业务只标记直接命中的加工分支；更深上游和下游不继承标签。条件候选始终保留候选状态。", "detail-kv"));
  const actions = el("div", null, "detail-actions");
  // 汇报演示版暂时隐藏这三个高级操作，保留底层实现以便后续恢复。
  if (node.parent_id) actions.append(button("查看所属表", () => focusNode(node.parent_id)));
  if (node.self_reference_count) actions.append(button(node.type === "table" ? "查看表内依赖" : "查看自身引用", () => edgeDetails({ source: node.id, target: node.id })));
  if (actions.childElementCount) box.append(actions);
  await businessDetails(box, node, revision);
  if (revision !== detailRevision) return;
  if (node.type === "table") {
    const section = el("section", null, "detail-section");
    section.append(el("h3", "字段目录 · " + node.field_count));
    section.append(el("p", "在本表中搜索并选择字段，下一步选择该字段对应的业务。目录包含已定义字段和仅被引用的字段。", "detail-kv"));
    const input = el("input", null, "field-search"); input.placeholder = "输入字段英文或中文名"; input.setAttribute("aria-label", "筛选此表字段");
    const list = el("div"); section.append(input, list); box.append(section);
    let fieldRevision = 0;
    const load = async (offset = 0) => {
      const requestRevision = ++fieldRevision;
      const fields = await api("nodes", { kind: "column", parent_id: node.id, q: input.value, limit: 15, offset });
      if (revision !== detailRevision || requestRevision !== fieldRevision) return;
      list.replaceChildren();
      for (const field of fields.items) { const item = button("", () => focusNode(field.id), "field-button"); item.dataset.fieldId = field.id; item.title = nodeTooltip(field); const status = field.mapping_pending ? "待匹配 · " + field.original_table : field.defined ? "选择字段 · 查看业务与血缘" : "仅被引用 · 查看血缘"; item.append(el("span", displayNodeName(field)), el("small", status, field.mapping_pending ? "mapping-pending" : "")); list.append(item); }
      pager(list, offset, 15, fields.total, load);
    };
    input.addEventListener("input", debounce(() => load()));
    await load();
  }
  if (revision !== detailRevision) return;
  const evidence = el("section", null, "detail-section"); box.append(evidence);
  await evidencePanel(evidence, node.id, businessMode ? { business_tag: business.tag } : {}, revision);
  if (revision !== detailRevision) return;
  if (businessMode) {
    const definitions = el("details", null, "detail-section business-secondary");
    const content = el("section");
    definitions.append(el("summary", "查看全部字段加工明细"), content);
    box.append(definitions);
    await definitionsPanel(content, node.id, revision);
  } else {
    const definitions = el("section", null, "detail-section"); box.append(definitions);
    await definitionsPanel(definitions, node.id, revision);
  }
}
function jsonDisclosure(title, value) { const item = el("details"); item.append(el("summary", title), el("pre", typeof value === "string" ? value : pretty(value), "code-block")); return item; }
async function evidencePanel(container, id, filter, revision, offset = 0, q = "") {
  const requestRevision = (panelRequestRevision.get(container) || 0) + 1;
  panelRequestRevision.set(container, requestRevision);
  const refocus = document.activeElement?.classList.contains("evidence-search");
  const tag = Object.prototype.hasOwnProperty.call(filter, "business_tag") ? filter.business_tag : "";
  const businessRoot = tag ? (filter.business_root || business.root) : "";
  const data = await api("evidence", { id, ...filter, business_tag: tag, business_root: businessRoot, q, offset, limit: 8 });
  if (revision !== detailRevision || panelRequestRevision.get(container) !== requestRevision) return;
  const filtered = Boolean(filter.source && filter.target);
  const count = q ? data.total + " / " + data.unfiltered_total : data.total;
  container.replaceChildren(el("h3", tag && id === business.root ? "当前业务来源依据" : tag ? "当前业务来源依据 · " + count : filtered ? "当前连线依据 · " + count : "直接关系依据 · " + count));
  if (business.tag && (id === business.root || tag)) {
    container.append(el("p", tag ? "仅展示当前业务“" + tag + "”命中的来源依据。" : "当前业务未命中这条关系，以下为该关系的直接依据。", "detail-kv"));
  }
  const search = el("input", null, "detail-search evidence-search");
  search.type = "search"; search.value = q; search.placeholder = "搜索来源、目标、业务、脚本或 SQL"; search.setAttribute("aria-label", "搜索直接关系依据");
  search.addEventListener("input", debounce(() => evidencePanel(container, id, { ...filter, business_tag: tag, business_root: businessRoot }, revision, 0, search.value.trim())));
  container.append(search);
  if (refocus) queueMicrotask(() => { search.focus(); search.setSelectionRange(search.value.length, search.value.length); });
  if (!filtered) {
    const counts = ["上游字段关系 " + data.direct_upstream, "下游字段关系 " + data.direct_downstream];
    if (data.internal) counts.push((id.startsWith("table:") ? "表内字段关系 " : "自身引用关系 ") + data.internal);
    container.append(el("p", counts.join(" · "), "evidence-counts"));
    container.append(el("p", "仅统计与当前对象直接相连的一跳关系，不包含第二层及更远的间接链路。", "detail-kv"));
  }
  if (!data.total) {
    container.append(el("p", q ? "没有匹配的关系依据，请调整搜索关键词。" : tag ? "当前没有命中此业务的来源依据，可查看业务分支或字段加工明细。" : "当前没有直接关系记录，可在字段加工明细中核对来源列表和原始逻辑。", "muted"));
    return;
  }
  const disclosure = el("details", null, "evidence-disclosure");
  disclosure.open = filtered || offset > 0;
  disclosure.append(el("summary", filtered ? "查看具体依据" : "展开查看 " + data.total + " 条来源依据"));
  const list = el("div"); disclosure.append(list); container.append(disclosure);
  for (const evidence of data.items) {
    const card = el("article", null, "evidence-card");
    const effectiveTag = (evidence.effective_business_tags || [evidence.effective_business_tag || ""]).join("、");
    if (evidence.business_line) card.append(el("small", "来源文件所属条线：" + evidence.business_line.toUpperCase()));
    const inheritedCaption = evidence.business_tag_inherited ? "（" + (evidence.business_label_method === "branch_group" ? "分支组传播自 " : "继承自 ") + evidence.business_tag_source_path + "）" : "";
    const resolution = evidence.business_label_resolution;
    const indirectCandidate = resolution?.method === "reference" && ["candidate", "partial"].includes(resolution.status);
    const tagCaption = effectiveTag ? (indirectCandidate ? " · 候选（间接追溯）：" : " · ") + effectiveTag + inheritedCaption : "";
    const sourceLabel = (evidence.source_chinese_name ? evidence.source_chinese_name + " · " : "") + (evidence.source_display_name || evidence.source.replace("column:", ""));
    const targetLabel = (evidence.target_chinese_name ? evidence.target_chinese_name + " · " : "") + (evidence.target_display_name || evidence.target.replace("column:", ""));
    card.append(el("b", evidence.path + tagCaption), el("small", sourceLabel + " → " + targetLabel), el("small", "文档：" + evidence.document), el("small", "脚本：" + evidence.script_name));
    if (indirectCandidate) card.append(el("small", "当前 JSON 仅记录 " + (resolution.raw_key || "业务标签字段") + " = " + resolution.raw_value + "；上述中文名称来自上游标签字段定义，尚未执行行级条件。", "mapping-pending"));
    if (evidence.mapping_basis?.rows?.length) {
      card.append(el("small", "原始关系：" + evidence.original_source.replace("column:", "") + " → " + evidence.original_target.replace("column:", "")), jsonDisclosure("表映射依据（不属于 SQL 加工步骤）", evidence.mapping_basis));
    }
    if (evidence.source_mapping_pending || evidence.target_mapping_pending) card.append(el("small", "涉及待匹配字段，当前保留原连接。", "mapping-pending"));
    if (evidence.logic.expression) card.append(jsonDisclosure("转换表达式", evidence.logic.expression));
    if (resolution) {
      const labelDetails = el("details"); labelDetails.append(el("summary", "标签解析依据 · " + labelStatus(resolution.status) + (resolution.method === "reference" ? " · 上游字段追溯" : "")));
      appendLabelEvidence(labelDetails, resolution); card.append(labelDetails);
    }
    card.append(jsonDisclosure("SQL / 条件 / 完整逻辑", evidence.logic));
    if (Object.keys(evidence.metadata).length) card.append(jsonDisclosure("转换来源定位", evidence.metadata));
    list.append(card);
  }
  pager(list, offset, 8, data.total, (next) => evidencePanel(container, id, { ...filter, business_tag: tag, business_root: businessRoot }, revision, next, q));
}
async function definitionsPanel(container, id, revision, offset = 0, q = "") {
  const requestRevision = (panelRequestRevision.get(container) || 0) + 1;
  panelRequestRevision.set(container, requestRevision);
  const refocus = document.activeElement?.classList.contains("definition-search");
  const data = await api("definitions", { id, q, offset, limit: 5 });
  if (revision !== detailRevision || panelRequestRevision.get(container) !== requestRevision) return;
  const count = q ? data.total + " / " + data.unfiltered_total : data.total;
  container.replaceChildren(el("h3", "字段加工明细 · " + count));
  container.append(el("p", "这里按原始 JSON 的 target 记录展示。一个字段在多个文件或多个分支中出现时，会保留多条定义；仅被下游引用、但没有本表 target 定义的字段不会出现在这里。", "detail-kv"));
  const search = el("input", null, "detail-search definition-search");
  search.type = "search"; search.value = q; search.placeholder = "搜索目标字段、表达式、SQL 或脚本"; search.setAttribute("aria-label", "搜索字段加工明细");
  search.addEventListener("input", debounce(() => definitionsPanel(container, id, revision, 0, search.value.trim())));
  container.append(search);
  if (refocus) queueMicrotask(() => { search.focus(); search.setSelectionRange(search.value.length, search.value.length); });
  if (!data.total) container.append(el("p", q ? "没有匹配的字段加工明细，请调整搜索关键词。" : "当前对象没有目标字段加工定义。", "muted"));
  for (const record of data.items) {
    const card = el("article", null, "evidence-card");
    if (record.business_line) card.append(el("small", "来源条线：" + record.business_line.toUpperCase()));
    if (record.mapping_basis?.rows?.length) card.append(el("small", "原始目标：" + record.original_target.replace("column:", "")), jsonDisclosure("表映射依据", record.mapping_basis));
    card.append(el("b", (record.target_chinese_name ? record.target_chinese_name + " · " : "") + (record.target_display_name || record.target.replace("column:", ""))), el("small", record.document + " · 第 " + (Number(record.index) + 1) + " 条加工记录"), el("small", record.sources.length ? record.sources.length + " 条来源" : Object.keys(record.transform_logic).length ? "有加工逻辑，未提供来源列表" : "来源与加工逻辑均为空（正常）"), jsonDisclosure("查看来源与加工详情", record));
    container.append(card);
  }
  pager(container, offset, 5, data.total, (next) => definitionsPanel(container, id, revision, next, q));
}
async function edgeDetails(edge) {
  const revision = ++detailRevision;
  const box = $("details-content");
  const internal = edge.source === edge.target;
  box.replaceChildren(el("h3", internal ? edge.source.startsWith("table:") ? "表内字段依赖" : "字段自身引用" : "当前连线详情"), el("p", displayEntityId(edge.source) + " → " + displayEntityId(edge.target), "detail-kv"));
  if (internal) box.append(el("p", "以下内容来自原始字段依赖记录，具体业务含义需结合 SQL 判断。", "detail-kv"));
  await evidencePanel(box.appendChild(el("section")), edge.source, { source: edge.source, target: edge.target, business_tag: business.tag && edge.business_match_count > 0 ? business.tag : "" }, revision);
}

async function showImpact(id = state.selected?.id) {
  if (!id) return notify("请先选择数据对象。");
  const revision = viewRevision;
  notify("正在计算下游可达范围…", false, true);
  const data = await api("impact", { id });
  if (revision !== viewRevision) return;
  state.impact = data; state.impactOffset = 0;
  $("impact-search").value = "";
  const box = $("impact-summary"); box.replaceChildren(el("p", "起点：" + displayEntityId(id), "detail-kv"));
  const counts = el("div", null, "impact-counts");
  for (const [label, value] of [["受影响实体", data.total], ["直接依赖", data.direct], ["间接依赖", data.indirect], ["涉及数据表", data.affected_tables.length]]) { const metric = el("div"); metric.append(el("strong", value), el("span", label)); counts.append(metric); }
  box.append(counts, el("p", (data.truncated ? "结果已截断，以上数量仅是已发现范围：" + data.reasons.map((r) => reasons[r]).join("；") + "。 " : "在本次查询预算和已导入关系内，已遍历全部下游。 ") + (data.approximation ? "表级范围按字段关系投影，可能高估；请进入字段核对。 " : "字段分析不包含未采集或仅出现在过滤条件中的依赖。 ") + "计数不包含起点。", "scope-warning"));
  renderImpact(); $("impact-dialog").showModal(); notify("影响分析完成。");
}
function renderImpact() {
  const data = state.impact; if (!data) return;
  const filtered = data.items.filter((n) => searchableNodeName(n).includes($("impact-search").value.toUpperCase()));
  const byId = new Map(data.items.map((n) => [n.id, n]));
  const box = $("impact-list"); box.replaceChildren();
  for (const node of filtered.slice(state.impactOffset, state.impactOffset + 25)) {
    const row = el("div", null, "impact-row"), name = el("div", null, "impact-name");
    name.append(el("b", displayBusinessQualified(node)), el("p", "最短距离 " + node.distance + " 跳 · " + (node.distance === 1 ? "直接依赖" : "间接依赖"), "detail-kv"));
    const path = [node.id]; let cursor = node;
    while (cursor?.predecessor && path.length <= data.items.length + 1) { path.push(cursor.predecessor); if (cursor.predecessor === data.root) break; cursor = byId.get(cursor.predecessor); }
    const pathNodes = [state.selected, ...(state.graph?.nodes || []), ...data.items].filter(Boolean);
    name.append(jsonDisclosure("查看一条最短见证路径", path.reverse().map((id) => displayEntityId(id, pathNodes)).join(" → ")));
    row.append(name, button("查看血缘", async () => { $("impact-dialog").close(); await focusNode(node.id); })); box.append(row);
  }
  if (!filtered.length) box.append(el("p", "没有匹配的下游实体。", "muted"));
  $("impact-page").textContent = (Math.floor(state.impactOffset / 25) + 1) + " / " + Math.max(1, Math.ceil(filtered.length / 25)) + " · " + filtered.length + " 个结果";
  $("impact-prev").disabled = state.impactOffset === 0; $("impact-next").disabled = state.impactOffset + 25 >= filtered.length;
}
async function loadIssues(reset = false) {
  if (reset) state.issueOffset = 0;
  const revision = ++issueRevision;
  const data = await api("issues", { code: $("issue-code").value, severity: $("issue-severity").value, q: $("issue-search").value, offset: state.issueOffset, limit: 30 });
  if (revision !== issueRevision) return;
  $("issue-total").textContent = "共 " + data.total.toLocaleString() + " 条诊断，原始数据和定位信息均保留。";
  const box = $("issues-list"); box.replaceChildren();
  for (const item of data.items) { const row = el("div", null, "issue-row"); row.append(el("b", labels[item.code] || item.code), el("small", { info: "提示", warning: "警告", error: "错误" }[item.severity] || item.severity), el("span", item.message), el("small", [item.document, item.location, item.target].filter(Boolean).join(" · "))); if (item.nodes) row.append(jsonDisclosure("循环实体", item.nodes)); box.append(row); }
  $("issues-page").textContent = (state.issueOffset / 30 + 1) + " / " + Math.max(1, Math.ceil(data.total / 30));
  $("issues-prev").disabled = state.issueOffset === 0; $("issues-next").disabled = state.issueOffset + 30 >= data.total;
}

function chooseFiles(event) {
  importController.choose(event);
}
async function importData(mode) {
  return importController.start(mode === "validate");
}
async function refreshAfterDocumentsChanged() {
  const previous = state.selected?.id;
  resetBusinessSelection(); renderBusinessBar();
  ++graphRevision; ++detailRevision; ++searchRevision;
  ++related.revision; related.root = null; related.data = null; related.pending = null;
  await refreshStats(); await search(true);
  if (previous) {
    try { await api("node", {id:previous}); await focusNode(previous); return; } catch { /* Select another object after replacement. */ }
  }
  const next = await api("nodes", {kind:"table", status:"defined", limit:1});
  if (next.items.length) await focusNode(next.items[0].id);
  else { state.graph = null; state.selected = null; $("graph-content").replaceChildren(); $("graph-empty").hidden = false; $("details-content").replaceChildren(); }
}
async function changeLineageView(next, explicitId) {
  const revision = ++viewRevision;
  const previous = state.selected;
  let target = explicitId || previous?.id;
  if (!explicitId && next === "raw" && previous?.original_ids?.length)
    target = previous.original_ids.includes(previous.id) ? previous.id : previous.original_ids[0];
  state.lineageView = next; $("lineage-view").value = next;
  ++graphRevision; ++detailRevision; ++searchRevision; ++issueRevision; ++mappingRevision; ++statsRevision;
  ++related.revision; related.root = null; related.data = null; related.pending = null;
  savedGraph = null; state.graph = null; state.selected = null; state.impact = null;
  resetBusinessSelection(); renderBusinessBar(); setPathMode(false);
  $("graph-content").replaceChildren(); $("details-content").replaceChildren();
  $("graph-empty").hidden = false; $("related-open").disabled = true;
  $("namespace").value = "";
  for (const id of ["related-dialog", "impact-dialog", "business-dialog", "mapping-dialog"]) if ($(id).open) $(id).close();
  await refreshStats(); if (revision !== viewRevision) return;
  await search(true); if (revision !== viewRevision) return;
  if (target) {
    let found;
    try { found = await api("node", { id: target }); } catch (error) { if (!error.data) throw error; }
    if (revision !== viewRevision) return;
    if (found) return focusNode(found.id);
  }
  const first = await api("nodes", { kind: "table", status: "defined", limit: 1 });
  if (revision === viewRevision && first.items.length) await focusNode(first.items[0].id);
}
async function loadMappingPending(reset = false) {
  if (reset) mappingOffset = 0;
  const revision = ++mappingRevision;
  const data = await api("mapping-status", { q: $("mapping-search").value, offset: mappingOffset, limit: 20 });
  if (revision !== mappingRevision) return;
  $("mapping-total").textContent = "当前匹配 " + data.total + " 个字段；未找到是指已导入记录中未见，不代表物理表没有此字段。";
  const list = $("mapping-list"); list.replaceChildren();
  for (const row of data.items) {
    const item = el("article", null, "mapping-member");
    item.append(el("b", row.source), el("small", "候选：" + row.target), el("small", row.message), button("查看保留血缘", async () => { $("mapping-dialog").close(); await focusNode(row.node_id); }));
    list.append(item);
  }
  if (!data.items.length) list.append(el("p", "当前没有符合条件的待匹配字段。", "muted"));
  $("mapping-page").textContent = (mappingOffset / 20 + 1) + " / " + Math.max(1, Math.ceil(data.total / 20));
  $("mapping-prev").disabled = mappingOffset === 0; $("mapping-next").disabled = mappingOffset + 20 >= data.total;
}
const importController = new LineageImportController(refreshAfterDocumentsChanged);
const documentManager = new LineageDocumentManager(refreshAfterDocumentsChanged, () => importController.busy);
documentManager.init();
$("lineage-view").onchange = run(() => changeLineageView($("lineage-view").value));
$("mapping-open").onclick = run(async () => { await loadMappingPending(true); $("mapping-dialog").showModal(); });
$("mapping-search").oninput = debounce(() => loadMappingPending(true));
$("mapping-prev").onclick = run(() => { mappingOffset -= 20; return loadMappingPending(); });
$("mapping-next").onclick = run(() => { mappingOffset += 20; return loadMappingPending(); });

$("search").addEventListener("input", debounce(() => search(true)));
for (const id of ["kind", "namespace", "status-filter"]) $(id).addEventListener("change", run(() => search(true)));
$("line-filter").onchange = run(() => { $("namespace").value = ""; $("status-filter").value = "defined"; return search(true); });
$("search-prev").onclick = run(() => { state.searchOffset -= 40; return search(); });
$("search-next").onclick = run(() => { state.searchOffset += 40; return search(); });
document.querySelectorAll("[data-trace]").forEach((b) => b.addEventListener("click", run(() => loadGraph(state.graph?.root || state.selected?.id, b.dataset.trace))));
for (const id of ["depth", "constants"]) $(id).addEventListener("change", run(() => loadGraph(state.graph?.root)));
$("impact").onclick = run(() => showImpact());
$("zoom-in").onclick = () => zoom(.8); $("zoom-out").onclick = () => zoom(1.25); $("fit").onclick = fitView;
$("import-open").onclick = () => { if ($("line-filter").value && !importController.session && !importController.busy) $("import-line").value = $("line-filter").value; $("import-dialog").showModal(); };
$("related-open").onclick = run(openRelated);
$("path-return").onclick = run(returnFromPath);
document.querySelectorAll("[data-related-direction]").forEach((button) => button.onclick = () => { related.direction = button.dataset.relatedDirection; related.offset = 0; renderRelated(); });
$("related-search").oninput = () => { related.offset = 0; renderRelated(); };
$("related-distance").onchange = () => { related.offset = 0; renderRelated(); };
$("related-prev").onclick = () => { related.offset -= 20; renderRelated(); };
$("related-next").onclick = () => { related.offset += 20; renderRelated(); };
$("quality-open").onclick = run(async () => { await loadIssues(true); $("quality-dialog").showModal(); });
$("stats-open").onclick = () => $("stats-dialog").showModal();
document.querySelectorAll("[data-close]").forEach((b) => { b.onclick = () => $(b.dataset.close).close(); });
for (const id of ["issue-code", "issue-severity"]) $(id).onchange = run(() => loadIssues(true));
$("issue-search").oninput = debounce(() => loadIssues(true));
$("issues-prev").onclick = run(() => { state.issueOffset -= 30; return loadIssues(); });
$("issues-next").onclick = run(() => { state.issueOffset += 30; return loadIssues(); });
$("impact-search").oninput = () => { state.impactOffset = 0; renderImpact(); };
$("impact-prev").onclick = () => { state.impactOffset -= 25; renderImpact(); };
$("impact-next").onclick = () => { state.impactOffset += 25; renderImpact(); };
$("file-input").onchange = chooseFiles; $("directory-input").onchange = chooseFiles;
$("validate-files").onclick = run(() => importData("validate")); $("import-files").onclick = run(() => importData("import"));
$("load-project").onclick = run(() => importData("project")); $("load-project-empty").onclick = run(() => importData("project"));
window.addEventListener("resize", debounce(fitView));
setupBusiness();
run(async () => {
  await configureDemo(); await refreshStats(); await search();
  await importController.init();
  if (state.demo?.demo_target_id) {
    await focusNode(state.demo.demo_target_id);
  } else if (state.stats.tables) {
    const preferred = await api("nodes", { kind: "table", status: "defined", limit: 1 });
    const initial = preferred.items.length ? preferred : await api("nodes", { kind: "table", limit: 1 });
    if (initial.items.length) await focusNode(initial.items[0].id);
  }
})();




