# 迁移与部署说明

## 首次迁移

1. 备份 Python V6 数据库、当前有效 JSON、表映射和名称字典。
2. 为 Java 版建立空的 SQLite 文件或空的 GaussDB schema，不要让 Java 直接连接 Python V6 的 schema。
3. 首次启动时通过 `LINEAGE_MAPPING_FILE` 和 `LINEAGE_NAME_DICTIONARY_FILE` 指向 V6 的 `FSIS湖表映射.xlsx` 与 `name_dictionary_seed.json`。Java 会校验并把初始化内容写入新数据库；原始内部配置文件不放入 Git。
4. 启动 Java 服务，确认 `/api/health` 返回 `status=ok`、`version=6.0.0`、`storage_schema_version=7`，并确认两个端到端报告特性均为 `false`。
5. 按条线把当前有效 JSON 全量导入一次。可以先“仅校验”，通过后直接确认导入，无需重复上传。
6. 对照文档数、表数、字段数、关系数、典型表路径、业务标签和待匹配字段。
7. 停止旧入口并切换访问地址。旧数据库继续只读保留到验收期结束。

Python V6 与 Java V6 都使用版本号 7，但文档缓存实现不同。Python 使用 `graph_data`，Java 使用 `normalized_key`。Java 启动时会检查该差异并给出迁移提示，不会把旧库当成 Java 库继续写入。

## 后续增量

首次迁移后，Java 数据库负责保存历史状态。每次只提交新增文件或内容发生变化的文件：

- 新 `line + dataset_id`：新增；
- 原身份、原摘要：跳过；
- 原身份、新摘要：覆盖；
- 删除：调用文档删除接口后重建快照；
- 条线更正：先预览并取得 `preview_token`，再作为后台任务提交。

不需要为了保留第一次结果而在第二次重复提交旧文件。

## SQLite 配置

SQLite 用于本地开发、演示和迁移验证：

```text
LINEAGE_DB_BACKEND=sqlite
LINEAGE_DB_URL=jdbc:sqlite:./var/lineage.sqlite3
```

数据库目录应位于持久化磁盘。不要把数据库、WAL、上传临时目录或真实业务 JSON 提交到 Git。

## GaussDB 配置

GaussDB 使用 PostgreSQL JDBC 驱动和独立 schema：

```text
LINEAGE_DB_BACKEND=gaussdb
LINEAGE_DB_URL=jdbc:postgresql://host:port/database
LINEAGE_DB_SCHEMA=lineage
LINEAGE_DB_USERNAME=service_user
LINEAGE_DB_PASSWORD=从受控环境注入
```

服务账号需要目标 schema 的建表、查询和写入权限。首次上线前应在内网 GaussDB 测试环境执行一次空库启动、增量新增、覆盖更新、失败回滚和重启恢复演练。

## 发布与回滚

构建命令：

```powershell
mvn clean verify
```

发布文件为 `lineage-start/target/lineage-start-1.0.0-SNAPSHOT.jar`。发布包不需要 Python、Node.js、`sqlglot` 或 Python 虚拟环境。

回滚时停止 Java 服务，恢复上一版 JAR 和对应数据库备份后再启动。不要让两个版本同时写同一数据库。上传暂存文件不属于业务历史，可以在服务停止后清理。
