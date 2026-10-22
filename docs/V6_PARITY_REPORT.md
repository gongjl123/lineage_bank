# V6 对照验收报告

对照基线为 2026-10-22 的 `lineage_gaussdb_v6`。Java 版明确排除端到端血缘差异报告、Excel 报告和 `/api/e2e/*`。

## 功能对照

| 范围 | Java 结果 |
| --- | --- |
| 标准和旧格式 JSON、schema 与语义校验 | 已实现 |
| EAST、STANDARD、STD、YBT 条线 | 已实现 |
| 新增、相同跳过、覆盖更新、回滚、重启恢复 | 已实现 |
| 文档查询、删除、条线更正预览与后台执行 | 已实现 |
| 表/字段/常量构图及 evidence、definitions、issues | 已实现 |
| 上下游、影响分析、关联表、最短表路径 | 已实现 |
| Druid SQL 值血缘判定，替代 sqlglot | 已实现 |
| FSIS 表映射、待匹配字段、原始/业务视图 | 已实现；POI 5.2.5 首次加载外部 Excel |
| 名称字典和中文搜索展示 | 已实现；首次加载外部 V6 JSON |
| 业务标签、直接入口、候选状态和方向规则 | 已实现 |
| 小批量、后台任务、磁盘分片上传与限制 | 已实现 |
| V6 静态前端 | 已迁移，资源内容与当前 V6 一致 |
| SQLite | 已集成测试和启动验证 |
| GaussDB | JDBC/Druid 实现完成；仍需在内网真实环境做上线演练 |
| 端到端差异报告 | 按要求排除，接口返回 404 |

## 2026-10-22 优化同步

- 上游业务追踪不会通过环路重新引入其他业务入口。
- 下游保留真实字段引用，包括回到根字段的环路边，但不继承业务标签。
- 映射目标表不存在时不制造虚假的目标字段或派生定义，原字段保持待处理状态。
- 业务映射视图和原始技术视图继续使用同一批底层证据。

Python 定向回归执行结果：`4 passed in 1.68s`。覆盖业务环路、缺失映射目标、导入/重启保持技术身份和 API 默认业务视图。Java 对应的 `testBusinessIndex`、`testMappedLineageGraph` 和 `testLineageStore` 均通过。

## Java 验证结果

`mvn clean verify` 通过，JaCoCo 门禁已启用。

| 模块 | 行覆盖率 | 方法覆盖率 |
| --- | ---: | ---: |
| lineage-api | 100.00% | 100.00% |
| lineage-app | 90.77% | 100.00% |
| lineage-common | 100.00% | 100.00% |
| lineage-domain | 97.11% | 100.00% |
| lineage-infrastructure | 93.02% | 100.00% |
| lineage-web | 96.02% | 100.00% |

打包后的 Spring Boot JAR 已做启动冒烟：静态首页返回 200，`/api/health` 返回 200，SQLite schema 为 7，`/api/e2e/catalog` 返回 404。

使用 V6 原始外部配置做了首次初始化验证：载入 1320 个表映射和 3870 个名称条目。随后不再传入 Excel/JSON 路径，用同一 Java 数据库重启，健康接口显示两类配置均来自 `database`，数量保持一致。

科技评审可直接查看 [核心类与主要方法调用关系图](JAVA_CLASS_METHOD_CALLS.png)；图中保留主要运行链路，省略 DTO、配置类和辅助方法。

## 有意保留的差异

- Python V6 数据库不能原地作为 Java 数据库使用；首次迁移使用空 Java 库并全量导入当前有效 JSON。
- SQL 值血缘判定由 Druid 实现。无法可靠解析的语句采用保守策略，保留显式来源关系，不会因为解析失败删除已有血缘。
- Java 运行包不包含 Python、`sqlglot`、Python 服务调用或端到端报告代码。
