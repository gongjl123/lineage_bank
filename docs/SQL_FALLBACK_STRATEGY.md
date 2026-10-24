# SQL 解析兜底与核查

系统以导入 JSON 中显式声明的来源关系为基础。SQL AST 用于补充值来源判定；SQL 无法解析或无法确定目标输出时，保留该关系，并明确标记为待核查。

## 处理顺序

1. 使用现有 `com.alibaba:druid:1.2.16`，依次尝试 PostgreSQL、Oracle 方言。
2. 对 `'CHN' AS X` 等投影片段，尝试仅补充 `SELECT` 解析。片段只能用于确认正向匹配；不能因为片段未匹配就排除显式关系。
3. 对多语句、语法错误、不完整片段、通配符输出、目标位置映射或其他无法判定的场景，保留 JSON 的显式关系并产生核查诊断。

不会通过正则猜出缺失的来源字段，也不会执行被导入的 SQL。上述兼容处理没有新增 Maven 依赖。

## 结果状态

关系证据 `/api/evidence` 的每条记录带有 `sql_analysis`：

| status | 含义 | 保留值来源关系 | requires_review |
| --- | --- | --- | --- |
| `ast_matched` | AST 中匹配目标输出或赋值列 | 是 | false |
| `parent_path_matched` | 有 SQL 证据的父路径输出目标字段 | 是 | false |
| `sibling_path_matched` | 同组兄弟路径的目标投影或赋值消费中间值，可跨多个步骤 | 是 | false |
| `ast_excluded` | 在当前值来源判定规则下，完整输出明确不匹配目标 | 否，仅不参与值来源追溯；原始关系证据仍保留 | false |
| `fragment_matched` | 有限投影片段匹配目标输出 | 是 | false |
| `json_explicit` | 没有 SQL 补充证据，采用 JSON 显式来源 | 是 | false |
| `fallback` | 无法可靠完成 SQL 值来源判定 | 是 | true |

例如：

```json
{
  "supplies_target_value": true,
  "status": "fallback",
  "reason": "parse_failed",
  "dialect": "",
  "requires_review": true
}
```

`requires_review=false` 只表示没有触发本兜底诊断，不等于完成了数据库实际执行、行级条件求值或血缘正确性认证。

## V6 值血缘修正（2026-10-24）

关系证据同时返回 `value_lineage_correction`，页面的来源依据卡片展示其 `message`：

- `single_character_output_alias_typo`：只有一个投影，输出别名与目标名相差一个字符，且 SQL 表达式与 JSON 表达式一致。表达式使用 AST 比较，保留字符串常量的大小写和内部空格；不会把 `'a b'` 与 `'AB'` 当成相同值。
- `parent_path_outputs_target`：子路径先生成中间值，具有 SQL 证据的父路径输出目标；返回 `source_path`、`target_path` 和 `intermediate_field`。
- `sibling_output_feeds_target`：当前来源实际参与的中间投影被同一直接父路径下的兄弟目标投影或 UPDATE 赋值消费；返回 `intermediate_field`、`intermediate_fields`，多步链还返回 `via_paths`。子查询按表别名追踪值投影，不将过滤条件、其他输出或其他分组混入值链。
- `declared_source_fallback`：同一加工定义中的全部来源路径都明确无法输出目标时，仍按 JSON 显式 sources 保留关系，`sql_analysis.status=fallback`、`reason=declared_source_fallback` 且 `requires_review=true`。已有明确目标值分支时，其他仅输出辅助字段的分支继续过滤。

修正不回写原始 JSON，也不制造中间物理字段节点。原始 sources、SQL、辅助路径证据继续可查。

## 保守处理的边界

- 一段 SQL 含多条语句时，不只选择其中一条来否定原始关系，标记 `multiple_statements`。
- `INSERT INTO T(X) SELECT Y AS HELPER ...` 可能按位置给 X 赋值，不能按 `HELPER` 别名排除 X；标记 `positional_insert`。
- 指定列的 `CREATE TABLE ... AS SELECT` 同样保留并核查，标记 `positional_create`。
- `SELECT *`、`SELECT A.*` 无法在没有表结构时列出全部输出字段，保留并核查。
- 缺失或无法解析的单表达式不构成排除未起别名输出的依据。
- `CONST:` 是 V6 常量标记，比较表达式时去掉该前缀；它不代表真实 SQL 语法。
- 没有显式来源时，不会凭空创建一条关系；这类情况沿用来源缺失诊断。

## 查找与处理

页面的“数据校验报告”可筛选“SQL 来源待核查”。关系证据卡同时展示保留说明。API 支持：

```text
GET /api/issues?code=SQL_ANALYSIS_FALLBACK
GET /api/stats
GET /api/evidence?id=column:SCHEMA.TABLE::FIELD
```

诊断包含文档、目标字段、transform_logic 路径和原因，同一派生目标的同一逻辑路径只产生一条诊断，避免多个来源重复告警。`stats.sql_analysis` 按关系统计状态及 `requires_review` 数量，该数量可能大于去重后的诊断条数。

原始 SQL、JSON 和显式关系随导入事务保存；重启会重新构图并恢复相同核查状态。修复 JSON 中的 SQL 后，按同一条线和 dataset_id 增量更新，系统重新计算状态并清除已解决的诊断。源 JSON 自身错误、缺少来源或列位置歧义需要核对源数据；解析器兜底不能自动证明这些关系正确。

## 验证口径

此前的本地语料扫描包含工作区中的历史版本、演示与转换结果，并非经过确认的在线 V6 数据库全量清单。21,772 个唯一 sql_fragment 中，Druid 单语句解析通过 20,978 个（96.35%）。该数字仅反映当次语料的语法解析情况，不能作为血缘判断正确率，也不证明所有 JSON 都通过了 schema 校验。

回归测试覆盖完整语句匹配与排除、投影片段、常量标记、多语句、位置赋值、通配符、坏表达式、别名修正与字符串反例、父子/兄弟路径、多步值链、UPDATE 目标赋值、子查询与辅助字段过滤、分组隔离、诊断去重、仅校验不提交、持久化重启和修复后的增量更新。
