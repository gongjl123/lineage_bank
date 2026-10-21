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
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Java replacement for V6's sqlglot based value-lineage classification.
 * A relation is removed only when parsed SQL clearly produces another field;
 * missing, unsupported, or invalid SQL is retained conservatively.
 */
@Component
public class DruidValueLineageDecider implements ValueLineageDecider {

    private final ConcurrentMap<String, Optional<ParsedSql>> cache = new ConcurrentHashMap<>();

    @Override
    public boolean suppliesTargetValue(Map<String, Object> relation) {
        Object value = relation.get("logic");
        Map<String, Object> logic = value instanceof Map<?, ?> map ? stringMap(map) : Map.of();
        String target = text(relation.getOrDefault("original_target", relation.get("target")));
        return logicSuppliesTargetValue(target, logic);
    }

    public boolean evidenceSuppliesTargetValue(Map<String, Object> evidence) {
        String target = text(evidence.getOrDefault("original_target", evidence.get("target")));
        return logicSuppliesTargetValue(target, Map.of(
                "sql_fragment", text(evidence.get("sql")),
                "expression", text(evidence.get("expression"))));
    }

    public boolean logicSuppliesTargetValue(String target, Map<String, Object> logic) {
        String sql = text(logic.get("sql_fragment")).trim();
        if (sql.isEmpty()) {
            return true;
        }
        Optional<ParsedSql> parsed = cache.computeIfAbsent(sql, this::parseSql);
        if (parsed.isEmpty() || target == null || target.isBlank()) {
            return true;
        }
        String targetField = target.substring(target.lastIndexOf("::") + 2).toUpperCase(Locale.ROOT);
        Boolean result = selectProjectsTarget(parsed.get(), targetField,
                text(logic.get("expression")).trim());
        if (result == null) {
            result = updateAssignsTarget(parsed.get().statement, targetField);
        }
        return result == null || result;
    }

    private Optional<ParsedSql> parseSql(String sql) {
        for (DbType dbType : List.of(DbType.postgresql, DbType.oracle)) {
            try {
                return Optional.of(new ParsedSql(SQLUtils.parseSingleStatement(sql, dbType), dbType));
            } catch (RuntimeException ignored) {
                // Try the next V6-compatible dialect.
            }
        }
        return Optional.empty();
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
        }
        return false;
    }

    private Boolean updateAssignsTarget(SQLStatement statement, String targetField) {
        if (!(statement instanceof SQLUpdateStatement update)) {
            return null;
        }
        List<SQLUpdateSetItem> assignments = update.getItems();
        if (assignments.isEmpty()) {
            return null;
        }
        return assignments.stream().map(SQLUpdateSetItem::getColumn).map(this::simpleName)
                .anyMatch(targetField::equals);
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

        private ParsedSql(SQLStatement statement, DbType dbType) {
            this.statement = statement;
            this.dbType = dbType;
        }
    }

    private static final class StarVisitor extends SQLASTVisitorAdapter {
        private boolean found;

        @Override
        public boolean visit(SQLAllColumnExpr item) {
            found = true;
            return false;
        }
    }
}
