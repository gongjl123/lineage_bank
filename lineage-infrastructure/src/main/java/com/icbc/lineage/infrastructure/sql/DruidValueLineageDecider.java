package com.icbc.lineage.infrastructure.sql;

import com.alibaba.druid.DbType;
import com.alibaba.druid.sql.SQLUtils;
import com.alibaba.druid.sql.ast.SQLExpr;
import com.alibaba.druid.sql.ast.SQLStatement;
import com.alibaba.druid.sql.ast.expr.SQLAllColumnExpr;
import com.alibaba.druid.sql.ast.expr.SQLIdentifierExpr;
import com.alibaba.druid.sql.ast.expr.SQLPropertyExpr;
import com.alibaba.druid.sql.ast.statement.SQLCreateTableStatement;
import com.alibaba.druid.sql.ast.statement.SQLInsertStatement;
import com.alibaba.druid.sql.ast.statement.SQLJoinTableSource;
import com.alibaba.druid.sql.ast.statement.SQLSelect;
import com.alibaba.druid.sql.ast.statement.SQLSelectItem;
import com.alibaba.druid.sql.ast.statement.SQLSelectQuery;
import com.alibaba.druid.sql.ast.statement.SQLSelectQueryBlock;
import com.alibaba.druid.sql.ast.statement.SQLSelectStatement;
import com.alibaba.druid.sql.ast.statement.SQLSubqueryTableSource;
import com.alibaba.druid.sql.ast.statement.SQLTableSource;
import com.alibaba.druid.sql.ast.statement.SQLUnionQuery;
import com.alibaba.druid.sql.ast.statement.SQLUpdateSetItem;
import com.alibaba.druid.sql.ast.statement.SQLUpdateStatement;
import com.alibaba.druid.sql.visitor.SQLASTVisitorAdapter;
import com.icbc.lineage.domain.graph.ValueLineageDecider;
import com.icbc.lineage.domain.graph.ValueLineageDecision;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Java replacement for V6's sqlglot based value-lineage classification.
 * A relation is removed only when parsed SQL clearly produces another field;
 * missing, unsupported, or invalid SQL is retained conservatively.
 */
@Component
public class DruidValueLineageDecider implements ValueLineageDecider {

    private final ConcurrentMap<String, ParsedSql> cache = new ConcurrentHashMap<>();

    @Override
    public boolean suppliesTargetValue(Map<String, Object> relation) {
        return assess(relation).suppliesTargetValue();
    }

    @Override
    public ValueLineageDecision assess(Map<String, Object> relation) {
        Object value = relation.get("logic");
        Map<String, Object> logic = value instanceof Map<?, ?> map ? stringMap(map) : Map.of();
        String target = text(relation.getOrDefault("original_target", relation.get("target")));
        return assessLogic(target, logic);
    }

    public boolean evidenceSuppliesTargetValue(Map<String, Object> evidence) {
        String target = text(evidence.getOrDefault("original_target", evidence.get("target")));
        return logicSuppliesTargetValue(target, Map.of(
                "sql_fragment", text(evidence.get("sql")),
                "expression", text(evidence.get("expression"))));
    }

    public boolean logicSuppliesTargetValue(String target, Map<String, Object> logic) {
        return assessLogic(target, logic).suppliesTargetValue();
    }

    public ValueLineageDecision assessLogic(String target, Map<String, Object> logic) {
        String sql = text(logic.get("sql_fragment")).trim();
        if (sql.isEmpty()) {
            return new ValueLineageDecision(true, "json_explicit", "missing_sql", "");
        }
        try {
            return analyze(target, logic, sql);
        } catch (RuntimeException exception) {
            // AST traversal/formatting can also reject a construct after successful parsing.
            return fallback("analysis_failed", "");
        }
    }

    @Override
    public List<ValueLineageDecision> assessDerivation(List<Map<String, Object>> relations,
                                                       Map<String, Map<String, Object>> transformLogic) {
        List<ValueLineageDecision> decisions = new ArrayList<>(relations.stream().map(this::assess).toList());
        for (int index = 0; index < relations.size(); index++) {
            ValueLineageDecision current = decisions.get(index);
            if (current.suppliesTargetValue() || current.requiresReview()) {
                continue;
            }
            Map<String, Object> correction = parentPathCorrection(relations.get(index), transformLogic);
            if (correction == null) {
                correction = siblingPathCorrection(relations.get(index), transformLogic);
            }
            if (correction != null) {
                String status = "parent_path_outputs_target".equals(correction.get("type"))
                        ? "parent_path_matched" : "sibling_path_matched";
                decisions.set(index, new ValueLineageDecision(true, status,
                        "target_value_confirmed_by_path_chain", current.dialect(), correction));
            }
        }
        boolean allClearlyExcluded = !decisions.isEmpty()
                && decisions.stream().allMatch(decision -> decision.status().equals("ast_excluded"));
        if (allClearlyExcluded) {
            for (int index = 0; index < relations.size(); index++) {
                Map<String, Object> correction = declaredSourceFallback(relations.get(index));
                decisions.set(index, new ValueLineageDecision(true, "fallback", "declared_source_fallback",
                        decisions.get(index).dialect(), correction));
            }
        }
        return List.copyOf(decisions);
    }

    private ValueLineageDecision analyze(String target, Map<String, Object> logic, String sql) {
        ParsedSql parsed = cache.computeIfAbsent(sql, this::parseSql);
        String dialect = parsed.dbType == null ? "" : parsed.dbType.name();
        if (!parsed.failure.isEmpty()) {
            return fallback(parsed.failure, dialect);
        }
        if (target == null || target.lastIndexOf("::") < 0 || target.endsWith("::")) {
            return fallback("missing_target", dialect);
        }
        String targetField = identifier(target.substring(target.lastIndexOf("::") + 2));
        SQLStatement statement = parsed.statement;
        // INSERT(a,b) SELECT x,y maps by position; projection aliases are not target column names.
        if (statement instanceof SQLInsertStatement insert && !insert.getColumns().isEmpty()) {
            return fallback("positional_insert", dialect);
        }
        if (statement instanceof SQLCreateTableStatement create && !create.getTableElementList().isEmpty()) {
            return fallback("positional_create", dialect);
        }
        Boolean result = selectProjectsTarget(parsed, targetField, text(logic.get("expression")).trim());
        if (Boolean.FALSE.equals(result)) {
            Map<String, Object> correction = singleProjectionAliasCorrection(parsed, targetField,
                    text(logic.get("expression")).trim());
            if (correction != null) {
                return new ValueLineageDecision(true, parsed.fragment ? "fragment_matched" : "ast_matched",
                        "single_character_output_alias_typo", dialect, correction);
            }
        }
        if (result == null) {
            result = updateAssignsTarget(statement, targetField);
        }
        if (result == null || parsed.fragment && !result) {
            return fallback(parsed.fragment ? "incomplete_sql_fragment" : "unresolved_statement", dialect);
        }
        return new ValueLineageDecision(result, parsed.fragment ? "fragment_matched"
                : result ? "ast_matched" : "ast_excluded", result ? "target_projected" : "target_not_projected", dialect);
    }

    private ParsedSql parseSql(String sql) {
        for (DbType dbType : List.of(DbType.postgresql, DbType.oracle)) {
            try {
                List<SQLStatement> statements = SQLUtils.parseStatements(sql, dbType);
                if (statements.size() > 1) {
                    return new ParsedSql(null, dbType, false, "multiple_statements");
                }
                if (statements.size() == 1) {
                    return new ParsedSql(statements.get(0), dbType, false, "");
                }
            } catch (RuntimeException ignored) {
                // Try the next V6-compatible dialect.
            }
        }
        // A projection fragment can prove a positive match, never a negative classification.
        if (!sql.contains(";") && !sql.matches("(?is)^(SELECT|WITH|INSERT|UPDATE|CREATE|DELETE|MERGE|SET|ALTER|DROP|BEGIN|DECLARE)\\b.*")) {
            for (DbType dbType : List.of(DbType.postgresql, DbType.oracle)) {
                try {
                    return new ParsedSql(SQLUtils.parseSingleStatement("SELECT " + sql, dbType), dbType, true, "");
                } catch (RuntimeException ignored) {
                    // Broken fragments remain explicit JSON relations, with a review warning.
                }
            }
        }
        return new ParsedSql(null, null, false, "parse_failed");
    }

    private ValueLineageDecision fallback(String reason, String dialect) {
        return new ValueLineageDecision(true, "fallback", reason, dialect);
    }

    private Boolean selectProjectsTarget(ParsedSql parsed, String targetField, String expression) {
        SQLSelect select = outputSelect(parsed.statement);
        if (select == null) {
            return null;
        }
        SQLSelectQuery query = select.getQuery();
        if (query instanceof SQLUnionQuery union) {
            query = union.getLeft();
        }
        if (!(query instanceof SQLSelectQueryBlock block)) {
            return null;
        }
        List<SQLSelectItem> projections = block.getSelectList();
        if (projections.isEmpty() || projections.stream().anyMatch(item -> containsStar(item.getExpr()))) {
            return null;
        }
        SQLExpr parsedExpression = parseExpression(expression, parsed.dbType);
        boolean unresolvedExpression = false;
        for (SQLSelectItem projection : projections) {
            String alias = projection.getAlias();
            if (alias != null) {
                if (identifier(alias).equals(targetField)) {
                    return true;
                }
                continue;
            }
            if (simpleName(projection.getExpr()).equals(targetField)) {
                return true;
            }
            if (parsedExpression != null && projection.getExpr().equals(parsedExpression)) {
                return true;
            }
            unresolvedExpression |= parsedExpression == null;
        }
        return unresolvedExpression ? null : false;
    }

    private Boolean updateAssignsTarget(SQLStatement statement, String targetField) {
        if (!(statement instanceof SQLUpdateStatement update)) {
            return null;
        }
        List<SQLUpdateSetItem> assignments = update.getItems();
        if (assignments.isEmpty()) {
            return null;
        }
        boolean unknownColumn = false;
        for (SQLUpdateSetItem assignment : assignments) {
            String name = simpleName(assignment.getColumn());
            if (targetField.equals(name)) {
                return true;
            }
            unknownColumn |= name.isEmpty();
        }
        return unknownColumn ? null : false;
    }

    private SQLSelect outputSelect(SQLStatement statement) {
        if (statement instanceof SQLSelectStatement select) {
            return select.getSelect();
        }
        if (statement instanceof SQLInsertStatement insert) {
            return insert.getQuery();
        }
        if (statement instanceof SQLCreateTableStatement create) {
            return create.getSelect();
        }
        return null;
    }

    private SQLSelectQueryBlock outputSelectBlock(SQLStatement statement) {
        SQLSelect select = outputSelect(statement);
        if (select == null) {
            return null;
        }
        SQLSelectQuery query = select.getQuery();
        if (query instanceof SQLUnionQuery union) {
            query = union.getLeft();
        }
        return query instanceof SQLSelectQueryBlock block ? block : null;
    }

    private Map<String, Object> parentPathCorrection(Map<String, Object> relation,
                                                     Map<String, Map<String, Object>> transformLogic) {
        String target = text(relation.getOrDefault("original_target", relation.get("target")));
        String path = text(relation.get("path"));
        Map<String, Object> logic = objectMap(relation.get("logic"));
        if (target.isBlank() || logicSuppliesTargetValue(target, logic)) {
            return null;
        }
        String parent = path;
        while (parent.contains("-")) {
            parent = parent.substring(0, parent.lastIndexOf('-'));
            Map<String, Object> parentLogic = transformLogic.get(parent);
            if (parentLogic == null || text(parentLogic.get("sql_fragment")).isBlank()) {
                continue;
            }
            ValueLineageDecision parentDecision = assessLogic(target, parentLogic);
            if (!parentDecision.suppliesTargetValue() || parentDecision.requiresReview()) {
                continue;
            }
            String targetField = fieldName(target);
            String intermediate = singleProjectedAlias(logic);
            String intermediateText = intermediate.isBlank() ? "中间结果" : "中间字段 " + intermediate;
            return result("type", "parent_path_outputs_target", "source_path", path,
                    "target_path", parent, "intermediate_field", intermediate,
                    "target_field", targetField,
                    "message", "来源路径 " + path + " 先生成" + intermediateText + "，父路径 " + parent
                            + " 再输出目标字段 " + targetField + "，已按完整加工链保留字段值血缘。");
        }
        return null;
    }

    private Map<String, Object> siblingPathCorrection(Map<String, Object> relation,
                                                      Map<String, Map<String, Object>> transformLogic) {
        String target = text(relation.getOrDefault("original_target", relation.get("target")));
        String path = text(relation.get("path"));
        Map<String, Object> logic = objectMap(relation.get("logic"));
        if (target.isBlank() || !path.contains("-") || logicSuppliesTargetValue(target, logic)) {
            return null;
        }
        String source = text(relation.getOrDefault("original_source", relation.get("source")));
        String sourceField = fieldName(source);
        if (sourceField.isBlank()) {
            return null;
        }
        List<String> intermediates = new ArrayList<>();
        for (ProjectionDependencies projection : projectionDependencies(text(logic.get("sql_fragment")))) {
            if (!projection.name().isBlank() && projection.fields().contains(sourceField)
                    && !intermediates.contains(projection.name())) {
                intermediates.add(projection.name());
            }
        }
        if (intermediates.isEmpty()) {
            return null;
        }
        String targetField = fieldName(target);
        String siblingParent = path.substring(0, path.lastIndexOf('-'));
        List<Map.Entry<String, Map<String, Object>>> siblings = transformLogic.entrySet().stream()
                .filter(entry -> !entry.getKey().equals(path) && entry.getKey().contains("-")
                        && entry.getKey().substring(0, entry.getKey().lastIndexOf('-')).equals(siblingParent))
                .toList();
        for (Map.Entry<String, Map<String, Object>> sibling : siblings) {
            List<String> consumed = intermediates.stream()
                    .filter(field -> targetOutputReferencesField(targetField, sibling.getValue(), field)).toList();
            if (!consumed.isEmpty()) {
                return siblingCorrection(path, sibling.getKey(), targetField, consumed.get(0), consumed, List.of());
            }
        }
        Deque<ChainStep> pending = new ArrayDeque<>();
        intermediates.forEach(field -> pending.addLast(new ChainStep(field, List.of(), List.of(field))));
        Set<String> visited = new HashSet<>(intermediates);
        while (!pending.isEmpty()) {
            ChainStep current = pending.removeFirst();
            for (Map.Entry<String, Map<String, Object>> sibling : siblings) {
                if (current.viaPaths().contains(sibling.getKey())) {
                    continue;
                }
                if (targetOutputReferencesField(targetField, sibling.getValue(), current.field())) {
                    return siblingCorrection(path, sibling.getKey(), targetField,
                            current.chainFields().get(0), current.chainFields(), current.viaPaths());
                }
                for (ProjectionDependencies projection : projectionDependencies(
                        text(sibling.getValue().get("sql_fragment")))) {
                    if (projection.name().isBlank() || visited.contains(projection.name())
                            || !projection.fields().contains(current.field())) {
                        continue;
                    }
                    visited.add(projection.name());
                    pending.addLast(new ChainStep(projection.name(), append(current.viaPaths(), sibling.getKey()),
                            append(current.chainFields(), projection.name())));
                }
            }
        }
        return null;
    }

    private Map<String, Object> siblingCorrection(String sourcePath, String targetPath, String targetField,
                                                  String firstField, List<String> fields, List<String> viaPaths) {
        String message = viaPaths.isEmpty()
                ? "来源路径 " + sourcePath + " 先生成中间字段 " + firstField + "，同组路径 " + targetPath
                + " 引用该字段并输出目标字段 " + targetField + "，已按完整加工链保留字段值血缘。"
                : "来源路径 " + sourcePath + " 先生成中间字段 " + firstField + "，经同组路径 "
                + String.join("、", viaPaths) + " 形成 " + String.join(" → ", fields) + "，再由路径 "
                + targetPath + " 输出目标字段 " + targetField + "，已按完整加工链保留字段值血缘。";
        Map<String, Object> correction = result("type", "sibling_output_feeds_target", "source_path", sourcePath,
                "target_path", targetPath, "intermediate_field", firstField, "intermediate_fields", fields,
                "target_field", targetField, "message", message);
        if (!viaPaths.isEmpty()) {
            correction.put("via_paths", viaPaths);
        }
        return correction;
    }

    private boolean targetOutputReferencesField(String targetField, Map<String, Object> logic, String field) {
        String sql = text(logic.get("sql_fragment")).trim();
        if (sql.isEmpty() || field.isBlank()) {
            return false;
        }
        if (projectionDependencies(sql).stream().anyMatch(projection -> projection.name().equals(targetField)
                && projection.fields().contains(field))) {
            return true;
        }
        ParsedSql parsed = cache.computeIfAbsent(sql, this::parseSql);
        if (!parsed.failure.isEmpty() || parsed.statement == null
                || !(parsed.statement instanceof SQLUpdateStatement update)) {
            return false;
        }
        for (SQLUpdateSetItem assignment : update.getItems()) {
            if (simpleName(assignment.getColumn()).equals(targetField)
                    && referencedFields(assignment.getValue()).contains(field)) {
                return true;
            }
        }
        return false;
    }

    private List<ProjectionDependencies> projectionDependencies(String sql) {
        if (sql.isBlank()) {
            return List.of();
        }
        ParsedSql parsed = cache.computeIfAbsent(sql, this::parseSql);
        if (!parsed.failure.isEmpty() || parsed.statement == null) {
            return List.of();
        }
        SQLSelectQueryBlock block = outputSelectBlock(parsed.statement);
        if (block == null) {
            return List.of();
        }
        List<ProjectionDependencies> result = new ArrayList<>();
        for (SQLSelectItem item : block.getSelectList()) {
            result.add(new ProjectionDependencies(projectionName(item),
                    expressionValueFields(item.getExpr(), block, new HashSet<>())));
        }
        return result;
    }

    private Set<String> expressionValueFields(SQLExpr expression, SQLSelectQueryBlock query, Set<String> seen) {
        FieldVisitor visitor = new FieldVisitor();
        expression.accept(visitor);
        Set<String> fields = new LinkedHashSet<>();
        visitor.references.forEach(reference -> fields.add(reference.field()));
        for (FieldReference reference : visitor.references) {
            for (SQLSubqueryTableSource source : subqueries(query.getFrom())) {
                String alias = identifier(source.getAlias());
                if (!reference.qualifier().isBlank() && !reference.qualifier().equals(alias)) {
                    continue;
                }
                SQLSelectQueryBlock inner = selectBlock(source.getSelect());
                if (inner == null) {
                    continue;
                }
                String key = System.identityHashCode(inner) + "::" + reference.field();
                if (!seen.add(key)) {
                    continue;
                }
                for (SQLSelectItem item : inner.getSelectList()) {
                    if (!projectionName(item).equals(reference.field())) {
                        continue;
                    }
                    fields.addAll(expressionValueFields(item.getExpr(), inner, seen));
                }
            }
        }
        return Set.copyOf(fields);
    }

    private SQLSelectQueryBlock selectBlock(SQLSelect select) {
        if (select == null) {
            return null;
        }
        SQLSelectQuery query = select.getQuery();
        if (query instanceof SQLUnionQuery union) {
            query = union.getLeft();
        }
        return query instanceof SQLSelectQueryBlock block ? block : null;
    }

    private List<SQLSubqueryTableSource> subqueries(SQLTableSource source) {
        if (source == null) {
            return List.of();
        }
        if (source instanceof SQLSubqueryTableSource subquery) {
            return List.of(subquery);
        }
        if (source instanceof SQLJoinTableSource join) {
            List<SQLSubqueryTableSource> result = new ArrayList<>(subqueries(join.getLeft()));
            result.addAll(subqueries(join.getRight()));
            return result;
        }
        return List.of();
    }

    private Set<String> referencedFields(SQLExpr expression) {
        FieldVisitor visitor = new FieldVisitor();
        expression.accept(visitor);
        return visitor.references.stream().map(FieldReference::field)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    private String projectionName(SQLSelectItem item) {
        String alias = item.getAlias();
        return alias == null || alias.isBlank() ? simpleName(item.getExpr()) : identifier(alias);
    }

    private String singleProjectedAlias(Map<String, Object> logic) {
        ParsedSql parsed = cache.computeIfAbsent(text(logic.get("sql_fragment")).trim(), this::parseSql);
        SQLSelectQueryBlock block = parsed.failure.isEmpty() ? outputSelectBlock(parsed.statement) : null;
        if (block == null || block.getSelectList().size() != 1) {
            return "";
        }
        return identifier(block.getSelectList().get(0).getAlias());
    }

    private Map<String, Object> declaredSourceFallback(Map<String, Object> relation) {
        String targetField = fieldName(text(relation.getOrDefault("original_target", relation.get("target"))));
        String projectedField = singleProjectedAlias(objectMap(relation.get("logic")));
        String detail = projectedField.isBlank()
                ? "SQL 片段未明确输出目标字段 " + targetField
                : "SQL 输出别名 " + projectedField + " 与目标字段 " + targetField + " 不同";
        return result("type", "declared_source_fallback", "target_field", targetField,
                "projected_field", projectedField,
                "message", detail + "；该加工定义没有其他可识别的目标值分支，已按 JSON 显式 sources 保留并标记待核查。");
    }

    private Map<String, Object> singleProjectionAliasCorrection(ParsedSql parsed, String targetField,
                                                               String expression) {
        SQLSelectQueryBlock block = outputSelectBlock(parsed.statement);
        if (block == null || block.getSelectList().size() != 1) {
            return null;
        }
        SQLSelectItem item = block.getSelectList().get(0);
        String projectedField = identifier(item.getAlias());
        if (projectedField.isBlank() || Math.min(projectedField.length(), targetField.length()) < 5
                || !projectedField.matches("[A-Z0-9_]+") || !targetField.matches("[A-Z0-9_]+")
                || !oneEditApart(targetField, projectedField)) {
            return null;
        }
        SQLExpr parsedExpression = parseExpression(expression, parsed.dbType);
        if (parsedExpression == null || !item.getExpr().equals(parsedExpression)) {
            return null;
        }
        return result("type", "single_character_output_alias_typo", "target_field", targetField,
                "projected_field", projectedField,
                "message", "SQL 输出别名 " + projectedField + " 与目标字段 " + targetField
                        + " 相差一个字符，已按显式来源保留字段值血缘。");
    }

    private boolean oneEditApart(String left, String right) {
        if (left.equals(right) || Math.abs(left.length() - right.length()) > 1) {
            return false;
        }
        if (left.length() == right.length()) {
            int differences = 0;
            for (int index = 0; index < left.length(); index++) {
                if (left.charAt(index) != right.charAt(index) && ++differences > 1) {
                    return false;
                }
            }
            return differences == 1;
        }
        String shorter = left.length() < right.length() ? left : right;
        String longer = left.length() < right.length() ? right : left;
        int shortIndex = 0;
        int longIndex = 0;
        int differences = 0;
        while (shortIndex < shorter.length() && longIndex < longer.length()) {
            if (shorter.charAt(shortIndex) == longer.charAt(longIndex)) {
                shortIndex++;
                longIndex++;
            } else if (++differences > 1) {
                return false;
            } else {
                longIndex++;
            }
        }
        return true;
    }

    private List<String> append(List<String> values, String value) {
        List<String> result = new ArrayList<>(values);
        result.add(value);
        return List.copyOf(result);
    }

    private String fieldName(String entity) {
        int separator = entity.lastIndexOf("::");
        return separator < 0 ? "" : identifier(entity.substring(separator + 2));
    }

    private Map<String, Object> objectMap(Object value) {
        return value instanceof Map<?, ?> map ? stringMap(map) : Map.of();
    }

    private static Map<String, Object> result(Object... values) {
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            result.put(String.valueOf(values[index]), values[index + 1]);
        }
        return result;
    }

    private SQLExpr parseExpression(String expression, DbType dbType) {
        if (expression == null || expression.isBlank()) {
            return null;
        }
        if (expression.startsWith("CONST:")) {
            expression = expression.substring(6);
        }
        try {
            return SQLUtils.toSQLExpr(expression, dbType);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private boolean containsStar(SQLExpr expression) {
        StarVisitor visitor = new StarVisitor();
        expression.accept(visitor);
        return visitor.found;
    }

    private String simpleName(SQLExpr expression) {
        if (expression instanceof SQLIdentifierExpr identifier) {
            return identifier(identifier.getName());
        }
        if (expression instanceof SQLPropertyExpr property) {
            return identifier(property.getName());
        }
        return "";
    }

    private String identifier(String value) {
        return value == null ? "" : value.replace("\"", "").toUpperCase(Locale.ROOT);
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private Map<String, Object> stringMap(Map<?, ?> value) {
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        value.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    private record ProjectionDependencies(String name, Set<String> fields) { }

    private record FieldReference(String qualifier, String field) { }

    private record ChainStep(String field, List<String> viaPaths, List<String> chainFields) { }

    private static final class FieldVisitor extends SQLASTVisitorAdapter {
        private final Set<FieldReference> references = new LinkedHashSet<>();

        @Override
        public boolean visit(SQLIdentifierExpr item) {
            references.add(new FieldReference("", item.getName().replace("\"", "").toUpperCase(Locale.ROOT)));
            return false;
        }

        @Override
        public boolean visit(SQLPropertyExpr item) {
            String qualifier = item.getOwner() == null ? "" : item.getOwner().toString();
            int dot = qualifier.lastIndexOf('.');
            if (dot >= 0) {
                qualifier = qualifier.substring(dot + 1);
            }
            references.add(new FieldReference(qualifier.replace("\"", "").toUpperCase(Locale.ROOT),
                    item.getName().replace("\"", "").toUpperCase(Locale.ROOT)));
            return false;
        }
    }

    private static final class ParsedSql {
        private final SQLStatement statement;
        private final DbType dbType;
        private final boolean fragment;
        private final String failure;

        private ParsedSql(SQLStatement statement, DbType dbType, boolean fragment, String failure) {
            this.statement = statement;
            this.dbType = dbType;
            this.fragment = fragment;
            this.failure = failure;
        }
    }

    private static final class StarVisitor extends SQLASTVisitorAdapter {
        private boolean found;

        @Override
        public boolean visit(SQLAllColumnExpr item) {
            found = true;
            return false;
        }

        @Override
        public boolean visit(SQLPropertyExpr item) {
            found |= "*".equals(item.getName());
            return !found;
        }
    }
}
