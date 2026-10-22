# V6 血缘系统 Java 迁移计划

## 1. 目标与基线

本工程将 `lineage_gaussdb_v6` 迁移为可独立运行的 Java 系统。Python V6 是功能和接口行为基线，
`F:/大模型/项目/工商银行/project/val-app/val-app` 是工程结构、JDK、Spring、Maven 和测试风格基线。

目标运行环境：

- JDK 17；
- Spring Boot 3.2.3；
- Maven 多模块工程；
- JUnit 4.13.2、Mockito 2.23.0；
- JaCoCo 0.8.8；
- GaussDB 为生产数据库，SQLite 为本地开发和兼容性测试数据库；
- 服务运行时不依赖 Python，也不调用 Python 服务。

## 2. 功能边界

### 2.1 必须迁移

1. EAST、STANDARD、STD、YBT 四条线 JSON 的标准格式和旧格式解析。
2. JSON Schema 与语义校验、问题清单、重复键和非法数值检查。
3. 全量导入、增量新增、同文档覆盖更新、内容相同跳过、失败事务回滚。
4. 文档清单、原文/标准化内容查看、条线纠正预览和执行。
5. 表、字段、常量节点以及显式 `sources` 关系构图。
6. 表级和字段级上下游、影响分析、关联表、两表最短路径。
7. FSIS 表映射、原始技术视图、业务映射视图和待匹配状态。
8. 表字段中英文名称字典、中文搜索和展示。
9. 业务标签解析、父路径继承、标签字段追溯、候选状态和直接业务分支高亮。
10. 目标字段值血缘过滤，排除只生成辅助字段的 SQL 路径。
11. 关系证据、字段定义、数据问题查询。
12. 小批量导入、分片上传、后台导入任务、大小和数量限制。
13. V6 现有静态前端及现有非 E2E HTTP 接口兼容。
14. SQLite/GaussDB 存储结构版本 7 的核心表及重启恢复。

### 2.2 明确排除

- 所有 `/api/e2e/*` 接口；
- 端到端差异分析、分类、试算和报告任务；
- Excel 端到端报告和证据包生成；
- 端到端报告前端页面及入口。

普通 `/api/graph`、`/api/business-lineage`、`/api/table-path` 属于日常血缘查询，继续保留。

## 3. 工程结构

工程使用与 `val-app` 一致的分层多模块结构：

| 模块 | 职责 |
| --- | --- |
| `lineage-common` | 通用常量、异常、摘要、分页及 JSON 工具 |
| `lineage-domain` | 文档、节点、关系、图快照、映射、名称和业务标签领域模型与算法 |
| `lineage-api` | HTTP 请求/响应 DTO 和应用服务接口 |
| `lineage-app` | 导入、校验、查询、文档纠正、上传和任务编排 |
| `lineage-infrastructure` | SQLite/GaussDB JDBC、结构迁移、SQL AST、JSON Schema、文件与 Excel 适配 |
| `lineage-web` | Spring MVC Controller、异常映射和接口参数约束 |
| `lineage-start` | Spring Boot 启动、配置、静态资源和装配 |

包名前缀统一为 `com.icbc.lineage`。领域层不依赖 Spring MVC 和数据库实现。

## 4. 关键技术方案

### 4.1 SQL 解析

复用内网父工程已有且与 JDK 17 兼容的 `com.alibaba:druid:1.2.16`：

- PostgreSQL/GaussDB 与 Oracle 方言依次尝试；
- 解析 SELECT、UNION、INSERT SELECT、CREATE TABLE AS SELECT、UPDATE；
- 解析 CASE、COALESCE、字段别名、表别名和子查询；
- 无 SQL、解析失败和未知语句采取保守策略，不删除已有血缘关系。

若真实语料回归发现 Druid 无法覆盖的语法，再通过独立适配接口增加 JSqlParser，不改变领域层。

### 4.2 图快照

- 关系方向固定为 `source -> target`；
- 导入事务提交后构建新的不可变图快照，再用原子引用切换在线查询；
- 查询期间继续使用旧快照，不读取半成品；
- 强连通分量使用迭代算法，避免长链递归溢出；
- 默认业务映射视图，同时保留原始技术视图查询参数。

### 4.3 增量与更新

Java 端持久化文档身份、内容摘要、标准化文档、原文、结构化关系和业务标签解析上下文：

- 新文档：追加记录并重建受影响快照；
- 同身份同摘要：跳过；
- 同身份新摘要：在一个事务中替换该文档贡献；
- 条线纠正：只更新文档归属和相关索引，不修改原始 JSON；
- 删除：删除指定文档贡献并重建引用索引；
- 服务重启：从关系表与压缩文档恢复同等查询结果。

离线工具输出中间解析结果的方案保留为兼容入口，但 Java 主系统不依赖该工具保存历史状态。

### 4.4 数据库

保留 V6 结构版本 7 的表语义：

- `schema_version`、`documents`、`payloads`、`import_batches`；
- `entities`、`derivations`、`relations`、`issues`；
- `fsis_table_mappings`、`table_mapping_config`；
- `name_dictionary`、`name_dictionary_config`。

SQL 通过方言接口隔离 SQLite 和 GaussDB 差异。密码只允许来自环境变量或受控密码文件。

## 5. HTTP 兼容范围

优先保持 V6 URL、查询参数、状态码和响应字段：

- 健康与统计：`/api/health`、`/api/stats`、`/api/lines`；
- 节点目录：`/api/nodes`、`/api/node`；
- 血缘：`/api/graph`、`/api/trace`、`/api/impact`、`/api/associated-tables`、`/api/table-path`；
- 业务：`/api/business-tags`、`/api/business-upstream`、`/api/business-lineage`、
  `/api/business-branches`、`/api/business-label-evidence`；
- 证据：`/api/evidence`、`/api/definitions`、`/api/issues`；
- 文档：`/api/documents`、`/api/document`、`/api/document-files`、条线纠正接口；
- 导入：`/api/import`、`/api/validate`、`/api/import-jobs`、`/api/import-sessions`；
- 配置数据：`/api/table-mappings`、`/api/mapping-status`、`/api/schema/{name}`。

`/api/health` 版本保持 `6.0.0`，并返回：

```json
{
  "features": {
    "end_to_end_report": false,
    "business_label_lineage_inheritance": false
  }
}
```

### 5.1 2026-10-22 V6 同步约束

本轮以 2026-10-22 的 V6 源码和测试为迁移基线，后续实现必须保留以下行为：

- 业务字段上游只允许所选业务的直接入口边；下游保留全部真实字段值引用，包括环路返回根字段的边，但这些下游边不继承业务标签；
- 映射目标表不存在时，不创建虚假的目标字段或派生定义，原字段保留并标记待处理状态；
- 发布包不包含端到端报告代码，`/api/e2e/*` 不注册，健康接口的两个功能标志均固定为 `false`。

## 6. 执行阶段与验收

### 阶段 0：工程与契约基线

- 建立七模块 Maven 工程、统一依赖、编码和覆盖率门禁；
- 固化 JSON Schema、样例数据和 V6 API 契约清单；
- 建立不包含业务数据、凭据和运行文件的 Git 仓库。

验收：JDK 17 下 `mvn test` 可运行，所有模块依赖方向符合第 3 节。

### 阶段 1：格式、校验与领域模型

- 标准/旧 JSON 适配；
- schema 与语义校验；
- 文档、派生、来源、逻辑、问题领域模型。

验收：V6 样例及对应校验场景通过 Java 单元测试。

### 阶段 2：原始图与查询算法

- 构图、循环检测、目录、上下游、影响、关联表、路径、证据和定义；
- 目标字段值血缘判定。

验收：节点数、关系数、路径方向、循环及查询预算与 V6 对齐。

### 阶段 3：映射、名称与业务标签

- FSIS 映射视图；
- 名称字典；
- 标签分类、CASE/COALESCE、引用解析、父路径与业务查询。

验收：映射连通性、标签直接分支、候选状态和不跨关系继承规则与 V6 对齐。

### 阶段 4：存储、增量和更新

- SQLite/GaussDB 表结构与事务仓储；
- 导入替换、跳过、回滚、重启恢复和条线纠正；
- 数据库版本检查与迁移。

验收：同文档重导、失败回滚、增量新增、覆盖更新、服务重启结果一致。

### 阶段 5：Web、上传任务与前端

- 非 E2E Controller；
- 分片上传与后台任务；
- V6 静态页面迁移并接入 Java API。

验收：接口契约回归通过，所有 `/api/e2e/*` 返回 404，页面无报告入口。

### 阶段 6：对照验收与发布

- 同一组样例分别运行 Python V6 与 Java，比较统计、目录和核心查询；
- 生成数据库迁移演练说明、部署说明和差异清单；
- 完成安全扫描和发布包验证。

验收：核心接口对照一致；新增 Java 代码行覆盖率不低于 70%，方法覆盖率 100%。

## 7. 测试与质量门禁

- 测试类遵循内网 Surefire 的 `test*.java` 命名规则；
- 领域算法使用纯 JUnit 4 单元测试；
- 应用服务使用 Mockito；
- Controller 使用 MockMvc；
- 仓储使用 SQLite 集成测试，GaussDB 使用独立环境契约测试；
- JaCoCo 只考核本工程 Java 生产代码，行覆盖率 `>= 0.70`，方法覆盖率 `= 1.00`；
- 每个迁移阶段完成后单独提交，便于后续按提交迁入内网。

## 8. Git 与迁移约束

- 远端：`git@github.com:gongjl123/lineage_bank.git`；
- 每个提交只包含可迁移源码、测试、schema、无业务数据样例和说明；
- 不提交数据库密码、内网地址、真实业务 JSON、上传目录、日志、数据库文件和构建产物；
- 保持模块和包路径稳定，后续可按提交或目录迁入内网工程。

## 9. 执行状态

- [x] 确认功能边界和参考工程版本；
- [x] 形成迁移计划；
- [x] 阶段 0：工程与契约基线；
- [x] 阶段 1：格式、校验与领域模型；
- [x] 阶段 2：原始图与查询算法；
- [x] 阶段 3：映射、名称与业务标签；
- [x] 阶段 4：存储、增量和更新；
- [x] 阶段 5：Web、上传任务与前端；
- [x] 阶段 6：对照验收与发布。
