# lineage-bank

V6 血缘系统的 Java 17 / Spring Boot 3.2.3 迁移工程。运行时完全使用 Java，不启动或调用 Python 服务；原系统中的 `sqlglot` 已由 Druid 1.2.16 和 Java 图算法替代。

工程结构、JDK、Spring、Maven、JUnit 和编码风格参考内网备份工程 `val-app/val-app`。本工程不包含端到端血缘差异报告功能，也不注册 `/api/e2e/*` 接口。

## 构建与运行

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-17'
mvn clean verify
java -jar lineage-start\target\lineage-start-1.0.0-SNAPSHOT.jar
```

默认访问地址为 `http://127.0.0.1:8769/`，默认使用 `./var/lineage.sqlite3`。生产 GaussDB 通过环境变量配置：

```text
LINEAGE_DB_BACKEND=gaussdb
LINEAGE_DB_URL=jdbc:postgresql://host:port/database
LINEAGE_DB_SCHEMA=lineage
LINEAGE_DB_USERNAME=...
LINEAGE_DB_PASSWORD=...
```

首次建库时可把 V6 的表映射 Excel 和名称字典 JSON 作为外部配置传入：

```text
LINEAGE_MAPPING_FILE=/secure-config/FSIS湖表映射.xlsx
LINEAGE_NAME_DICTIONARY_FILE=/secure-config/name_dictionary_seed.json
```

Java 使用与参考工程兼容的 Apache POI 5.2.5 读取 Excel。初始化数据会写入数据库；以后重启以数据库内容为准，不要求配置文件一直存在。映射文件含内部业务信息，不随源码提交到 Git。

密码不写入配置文件。上传目录和大小限制可通过 `LINEAGE_UPLOAD_DIR`、`LINEAGE_MAX_FILE_MB`、`LINEAGE_MAX_BATCH_MB`、`LINEAGE_MAX_STAGED_MB` 和 `LINEAGE_UPLOAD_TTL_SECONDS` 调整。

## 增量与更新语义

- Java 服务在 SQLite 或 GaussDB 中保存历史文档和图快照。
- 第二次只需提交新增或本次要更新的 JSON，不用再次提交第一次的文件。
- 同一条线、同一 `dataset_id` 且摘要相同会跳过；摘要变化会覆盖更新。
- 新文件、覆盖更新、批量删除和条线更正均在事务提交后原子切换在线快照。
- 仅校验和失败导入不改变数据库与在线查询结果。
- 服务重启后从持久化文档恢复相同的血缘结果。

首次从 Python V6 切换到 Java 时，应建立新的 Java 数据库并将当前有效 JSON 全量导入一次。两边都标记为 schema 7，但内部缓存列不同，Java 会明确拒绝直接打开 Python V6 数据库，防止误读。首次迁移完成后的日常导入继续使用上述增量语义。

## 模块

| 模块 | 职责 |
| --- | --- |
| `lineage-common` | 通用摘要工具 |
| `lineage-domain` | 图、查询、映射、名称和业务标签算法 |
| `lineage-api` | 跨层 DTO 与存储接口 |
| `lineage-infrastructure` | JSON 校验、Druid SQL 分析、POI 配置初始化、SQLite/GaussDB JDBC |
| `lineage-app` | 增量导入、事务快照、任务和分片上传 |
| `lineage-web` | V6 非 E2E HTTP 接口 |
| `lineage-start` | Spring Boot 启动、配置和 V6 静态前端 |

`mvn verify` 强制检查每个含生产代码和测试的模块：行覆盖率不低于 70%，方法覆盖率为 100%。

进一步说明：

- [V6 Java 迁移计划](docs/V6_JAVA_MIGRATION_PLAN.md)
- [迁移与部署说明](docs/MIGRATION_AND_DEPLOYMENT.md)
- [V6 对照验收报告](docs/V6_PARITY_REPORT.md)
- [离线中间结果兼容方案](docs/OFFLINE_INTERMEDIATE_FORMAT.md)
- [核心类与方法调用关系图（PNG）](docs/JAVA_CLASS_METHOD_CALLS.png)
- [类图源文件（PlantUML）](docs/JAVA_CLASS_METHOD_CALLS.puml)
