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
import com.alibaba.druid.sql.ast.statement.SQLSelect;
import com.alibaba.druid.sql.ast.statement.SQLSelectItem;
import com.alibaba.druid.sql.ast.statement.SQLSelectQuery;
import com.alibaba.druid.sql.ast.statement.SQLSelectQueryBlock;
import com.alibaba.druid.sql.ast.statement.SQLSelectStatement;
import com.alibaba.druid.sql.ast.statement.SQLUnionQuery;
import com.alibaba.druid.sql.ast.statement.SQLUpdateSetItem;
import com.alibaba.druid.sql.ast.statement.SQLUpdateStatement;
import com.alibaba.druid.sql.visitor.SQLASTVisitorAdapter;
import com.icbc.lineage.domain.graph.ValueLineageDecider;
import com.icbc.lineage.domain.graph.ValueLineageDecision;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;
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
            if (parsedExpression != null && normalized(projection.getExpr(), parsed.dbType)
                    .equals(normalized(parsedExpression, parsed.dbType))) {
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

    private String normalized(SQLExpr expression, DbType dbType) {
        return SQLUtils.toSQLString(expression, dbType).replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
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
