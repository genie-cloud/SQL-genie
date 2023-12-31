package io.github.genie.sql.executor.jdbc;

import io.github.genie.sql.api.*;
import io.github.genie.sql.api.From.Entity;
import io.github.genie.sql.api.From.SubQuery;
import io.github.genie.sql.api.Order.SortOrder;
import io.github.genie.sql.api.Selection.MultiColumn;
import io.github.genie.sql.api.Selection.SingleColumn;
import io.github.genie.sql.builder.Expressions;
import io.github.genie.sql.builder.meta.*;
import io.github.genie.sql.executor.jdbc.JdbcQueryExecutor.PreparedSql;
import io.github.genie.sql.executor.jdbc.JdbcQueryExecutor.QuerySqlBuilder;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public class MySqlQuerySqlBuilder implements QuerySqlBuilder {


    public static final String NONE_DELIMITER = "";
    public static final String DELIMITER = ",";
    public static final String FOR_SHARE = " for share";
    public static final String FOR_UPDATE = " for update";
    public static final String FOR_UPDATE_NOWAIT = " for update nowait";
    public static final String SELECT = "select ";
    public static final String FROM = "from ";
    public static final String WHERE = " where ";
    public static final String HAVING = " having ";
    public static final String ORDER_BY = " order by ";
    public static final String DESC = "desc";
    public static final String ASC = "asc";
    public static final String ON = " on ";

    @Override
    public PreparedSql build(QueryStructure structure, Metamodel mappings) {
        return new Builder(structure, mappings).build();
    }


    static class Builder {

        protected final StringBuilder sql;
        protected final List<Object> args;
        protected final Map<Column, Integer> joins = new LinkedHashMap<>();
        protected final QueryStructure queryStructure;

        protected final EntityType entity;
        protected final Metamodel mappers;
        protected final List<Expression> selectedExpressions = new ArrayList<>();
        protected final List<Attribute> selectedAttributes = new ArrayList<>();

        protected final String fromAlias;
        protected final int subIndex;
        protected final AtomicInteger selectIndex;

        public Builder(StringBuilder sql,
                       List<Object> args,
                       QueryStructure queryStructure,
                       Metamodel mappers,
                       AtomicInteger selectIndex,
                       int subIndex) {
            this.sql = sql;
            this.args = args;
            this.queryStructure = queryStructure;
            this.mappers = mappers;
            this.subIndex = subIndex;
            this.selectIndex = selectIndex;
            Class<?> type = queryStructure.from().type();
            String prefix;
            if (queryStructure.from() instanceof Entity) {
                prefix = fixSymbol(type.getSimpleName());
                this.entity = mappers.getEntity(type);
            } else {
                prefix = "t";
                this.entity = null;
            }
            fromAlias = subIndex == 0 ? prefix + "_" : prefix + subIndex + "_";
        }

        public Builder(QueryStructure queryStructure, Metamodel mappers) {
            this(new StringBuilder(), new ArrayList<>(), queryStructure, mappers, new AtomicInteger(), 0);
        }

        protected PreparedSql build() {
            doBuilder();
            return new PreparedSqlImpl(sql.toString(), args, selectedAttributes);
        }

        private void doBuilder() {
            buildProjectionPaths();
            sql.append(SELECT);
            appendSelects();
            appendFetchPath();
            appendFrom();
            int joinIndex = sql.length();
            appendWhere();
            appendGroupBy();
            appendOrderBy();
            appendHaving();
            appendOffsetAndLimit();
            insertJoin(joinIndex);
            appendLockModeType(queryStructure.lockType());
        }

        private void buildProjectionPaths() {
            Selection selected = queryStructure.select();
            if (selected instanceof SingleColumn singleColumn) {
                selectedExpressions.add(singleColumn.column());
            } else if (selected instanceof MultiColumn multiColumn) {
                selectedExpressions.addAll(multiColumn.columns());
            } else if (queryStructure.select().resultType() == queryStructure.from().type()) {
                EntityType table = mappers
                        .getEntity(queryStructure.select().resultType());
                for (Attribute mapping : table.fields()) {
                    if (!(mapping instanceof BasicAttribute column)) {
                        continue;
                    }
                    Column columns = Expressions.ofPath(column.name());
                    selectedExpressions.add(columns);
                    selectedAttributes.add(mapping);
                }
            } else {
                Projection projectionMapping = mappers
                        .getProjection(queryStructure.from().type(), queryStructure.select().resultType());
                for (ProjectionAttribute mapping : projectionMapping.attributes()) {
                    if (!(mapping.baseField() instanceof BasicAttribute column)) {
                        continue;
                    }

                    Column columns = Expressions.ofPath(column.name());
                    selectedExpressions.add(columns);
                    selectedAttributes.add(mapping.field());
                }
            }
        }

        private static int unwrap(Integer offset) {
            return offset == null ? -1 : offset;
        }

        private void appendSelects() {
            String join = NONE_DELIMITER;
            for (Expression expression : selectedExpressions) {
                sql.append(join);
                appendExpression(expression);
                appendSelectAlias();
                join = DELIMITER;
            }
        }

        private void appendSelectAlias() {
            if (subIndex > 0) {
                sql.append(" as _").append(selectIndex.getAndIncrement());
            }
        }

        protected void appendFetchPath() {
            List<? extends Column> fetchClause = queryStructure.fetch();
            if (fetchClause != null) {
                for (Column fetch : fetchClause) {
                    Attribute attribute = getAttribute(fetch);
                    if (!(attribute instanceof AnyToOneAttribute am)) {
                        continue;
                    }
                    EntityType entityTypeInfo = am.referenced();
                    for (Attribute field : entityTypeInfo.fields()) {
                        if (!(field instanceof BasicAttribute mapping)) {
                            continue;
                        }
                        sql.append(",");
                        Column column = Expressions.concat(fetch, mapping.name());
                        appendPaths(column);
                        appendSelectAlias();
                        selectedExpressions.add(column);
                        selectedAttributes.add(field);
                    }
                }
            }
        }


        protected void appendLockModeType(LockModeType lockModeType) {
            if (lockModeType == LockModeType.PESSIMISTIC_READ) {
                sql.append(FOR_SHARE);
            } else if (lockModeType == LockModeType.PESSIMISTIC_WRITE) {
                sql.append(FOR_UPDATE);
            } else if (lockModeType == LockModeType.PESSIMISTIC_FORCE_INCREMENT) {
                sql.append(FOR_UPDATE_NOWAIT);
            }
        }

        private void appendFrom() {
            appendBlank().append(FROM);
            From from = queryStructure.from();
            if (from instanceof Entity) {
                appendFromTable();
            } else if (from instanceof SubQuery subQuery) {
                appendSubQuery(subQuery.queryStructure());
            }
            appendFromAlias();
        }

        private void appendSubQuery(QueryStructure queryStructure) {
            sql.append('(');
            new Builder(sql, args, queryStructure, mappers, selectIndex, subIndex + 1).doBuilder();
            sql.append(") ");
        }

        private void appendFromTable() {
            sql.append("`")
                    .append(entity.tableName())
                    .append("` ");
        }

        protected StringBuilder appendFromAlias() {
            return appendFromAlias(sql);
        }

        protected StringBuilder appendFromAlias(StringBuilder sql) {
            return sql.append(fromAlias);
        }

        protected StringBuilder appendTableAlias(String table, Object index, StringBuilder sql) {
            StringBuilder append = appendBlank(sql).append(fixSymbol(table));
            if (subIndex > 0) {
                sql.append(subIndex).append("_");
            }
            return append.append(index).append("_");
        }

        @NotNull
        private static String fixSymbol(String symbol) {
            return symbol.toLowerCase().substring(0, Math.min(4, symbol.length()));
        }

        protected StringBuilder appendBlank() {
            return appendBlank(sql);
        }

        protected StringBuilder appendBlank(StringBuilder sql) {
            return sql.isEmpty() || " (,+-*/=><".indexOf(sql.charAt(sql.length() - 1)) >= 0 ? sql : sql.append(' ');
        }


        protected void appendWhere() {
            Expression where = queryStructure.where();
            if (where == null || Expressions.isTrue(where)) {
                return;
            }
            sql.append(WHERE);
            appendExpression(where);
        }

        protected void appendHaving() {
            Expression having = queryStructure.having();
            if (having == null || Expressions.isTrue(having)) {
                return;
            }
            sql.append(HAVING);
            appendExpression(having);
        }

        protected void appendExpression(Expression expr) {
            appendExpression(args, expr);
        }


        protected void appendExpression(List<Object> args, Expression expression) {
            if (expression instanceof Constant constant) {
                appendConstant(args, constant);
            } else if (expression instanceof Column column) {
                appendPaths(column);
            } else if (expression instanceof Operation operation) {
                appendOperation(args, operation);
            } else {
                throw new UnsupportedOperationException("unknown type " + expression.getClass());
            }
        }

        private void appendConstant(List<Object> args, Constant constant) {
            Object value = constant.value();
            if (value instanceof Boolean b) {
                appendBlank().append(b ? 1 : 0);
            } else {
                appendBlank().append('?');
                args.add(value);
            }
        }

        private void appendOperation(List<Object> args, Operation operation) {
            Operator operator = operation.operator();
            Expression leftOperand = operation.operand();
            Operator operator0 = getOperator(leftOperand);
            List<? extends Expression> rightOperand = operation.args();
            switch (operator) {
                case NOT -> {
                    appendOperator(operator);
                    sql.append(' ');
                    if (operator0 != null && operator0.priority() > operator.priority()) {
                        sql.append('(');
                        appendExpression(args, leftOperand);
                        sql.append(')');
                    } else {
                        appendExpression(args, leftOperand);
                    }
                }
                case AND, OR, LIKE, MOD, GT, EQ, NE, GE, LT,
                        LE, ADD, SUBTRACT, MULTIPLY, DIVIDE -> {
                    appendBlank();
                    if (operator0 != null && operator0.priority() > operator.priority()) {
                        sql.append('(');
                        appendExpression(args, leftOperand);
                        sql.append(')');
                    } else {
                        appendExpression(args, leftOperand);
                    }
                    for (Expression value : rightOperand) {
                        appendOperator(operator);
                        Operator operator1 = getOperator(value);
                        if (operator1 != null && operator1.priority() >= operator.priority()) {
                            sql.append('(');
                            appendExpression(args, value);
                            sql.append(')');
                        } else {
                            appendExpression(args, value);
                        }
                    }
                }
                case LOWER, UPPER, SUBSTRING, TRIM, LENGTH,
                        NULLIF, IF_NULL, MIN, MAX, COUNT, AVG, SUM -> {
                    appendOperator(operator);
                    sql.append('(');
                    appendExpression(args, leftOperand);
                    for (Expression expression : rightOperand) {
                        sql.append(',');
                        appendExpression(args, expression);
                    }
                    sql.append(")");
                }
                case IN -> {
                    if (rightOperand.isEmpty()) {
                        appendBlank().append(0);
                    } else {
                        appendBlank();
                        appendExpression(leftOperand);
                        appendOperator(operator);
                        char join = '(';
                        for (Expression expression : rightOperand) {
                            sql.append(join);
                            appendExpression(args, expression);
                            join = ',';
                        }
                        sql.append(")");
                    }
                }
                case BETWEEN -> {
                    appendBlank();
                    appendExpression(args, leftOperand);
                    appendOperator(operator);
                    appendBlank();
                    Expression operate = Expressions
                            .operate(rightOperand.get(0), Operator.AND, List.of(rightOperand.get(1)));
                    appendExpression(args, operate);
                }
                case IS_NULL, IS_NOT_NULL -> {
                    appendBlank();
                    if (operator0 != null && operator0.priority()
                                             > operator.priority()) {
                        sql.append('(');
                        appendExpression(args, leftOperand);
                        sql.append(')');
                    } else {
                        appendExpression(args, leftOperand);
                    }
                    appendBlank();
                    appendOperator(operator);
                }
                default -> throw new UnsupportedOperationException("unknown operator " + operator);
            }
        }

        private void appendOperator(Operator jdbcOperator) {
            String sign = jdbcOperator.sign();
            if (Character.isLetter(sign.charAt(0))) {
                appendBlank();
            }
            sql.append(sign);
        }


        protected void appendPaths(Column column) {
            appendBlank();
            List<String> expression = column.paths();
            StringBuilder sb = sql;
            int iMax = expression.size() - 1;
            if (iMax == -1)
                return;
            int i = 0;
            if (expression.size() == 1) {
                appendFromAlias().append(".");
            }
            Class<?> type = queryStructure.from().type();

            Column join = Expressions.ofPaths(List.of(expression.get(0)));

            for (String path : expression) {
                EntityType info = mappers.getEntity(type);
                Attribute attribute = info.getAttribute(path);
                if (i++ == iMax) {
                    if (attribute instanceof AnyToOneAttribute joinColumnMapper) {
                        sb.append(joinColumnMapper.joinColumnName());
                    } else if (attribute instanceof BasicAttribute basicColumnMapper) {
                        sb.append(basicColumnMapper.columnName());
                    } else {
                        throw new IllegalStateException();
                    }
                    return;
                } else {
                    joins.putIfAbsent(join, joins.size());
                    if (i == iMax) {
                        Integer index = joins.get(join);
                        appendTableAttribute(sb, attribute, index).append('.');
                    }
                }
                type = attribute.javaType();
                join = Expressions.concat(join, path);
            }
        }

        protected void insertJoin(int sqlIndex) {
            StringBuilder sql = new StringBuilder();

            joins.forEach((k, v) -> {
                Attribute attribute = getAttribute(k);
                EntityType entityTypeInfo = mappers.getEntity(attribute.javaType());
                sql.append(" left join `").append(entityTypeInfo.tableName()).append("`");

                appendTableAttribute(sql, attribute, v);
                sql.append(ON);
                Column parent = getParent(k);
                if (parent == null) {
                    appendFromAlias(sql);
                } else {
                    Integer parentIndex = joins.get(parent);
                    Attribute parentAttribute = getAttribute(parent);
                    appendTableAttribute(sql, parentAttribute, parentIndex);
                }
                if (attribute instanceof AnyToOneAttribute join) {
                    sql.append(".").append(join.joinColumnName()).append("=");
                    appendTableAttribute(sql, attribute, v);
                    String referenced = join.referencedColumnName();
                    if (referenced.isEmpty()) {
                        referenced = ((BasicAttribute) entityTypeInfo.id()).columnName();
                    }
                    sql.append(".").append(referenced);
                } else {
                    throw new IllegalStateException();
                }
            });
            this.sql.insert(sqlIndex, sql);

        }

        private static Column getParent(Column k) {
            if (k == null || k.paths().size() <= 1) {
                return null;
            }
            List<String> paths = new ArrayList<>(k.paths());
            paths.remove(paths.size() - 1);
            return Expressions.ofPaths(paths);
        }

        Operator getOperator(Expression e) {
            return e instanceof Operation expression ? expression.operator() : null;
        }

        protected StringBuilder appendTableAttribute(StringBuilder sb, Attribute attribute, Integer index) {
            EntityType information = mappers.getEntity(attribute.javaType());
            String tableName = information.javaType().getSimpleName();
            return appendTableAlias(tableName, index, sb);
        }

        protected Attribute getAttribute(Column path) {
            Type schema = entity;
            for (String s : path.paths()) {
                if (schema instanceof AnyToOneAttribute associationProperty) {
                    schema = associationProperty.referenced();
                }
                if (schema instanceof EntityType ts) {
                    schema = ts.getAttribute(s);
                } else {
                    throw new IllegalStateException();
                }
            }
            return (Attribute) schema;
        }

        protected void appendOffsetAndLimit() {
            int offset = unwrap(queryStructure.offset());
            int limit = unwrap(queryStructure.limit());
            if (offset > 0) {
                sql.append(" limit ?,?");
                args.add(offset);
                args.add(limit < 0 ? Long.MAX_VALUE : limit);
            } else if (limit >= 0) {
                sql.append(" limit ");
                if (limit <= 1) {
                    sql.append(limit);
                } else {
                    sql.append("?");
                    args.add(limit);
                }
            }
        }

        private void appendGroupBy() {
            List<? extends Expression> groupBy = queryStructure.groupBy();
            if (groupBy != null && !groupBy.isEmpty()) {
                sql.append(" group by ");
                boolean first = true;
                for (Expression e : groupBy) {
                    if (first) {
                        first = false;
                    } else {
                        sql.append(",");
                    }
                    appendExpression(e);
                }
            }
        }

        protected void appendOrderBy() {
            List<? extends Order<?>> orders = queryStructure.orderBy();
            if (orders != null && !orders.isEmpty()) {
                sql.append(ORDER_BY);
                boolean first = true;
                for (Order<?> order : orders) {
                    if (first) {
                        first = false;
                    } else {
                        sql.append(",");
                    }
                    appendExpression(order.expression());
                    sql.append(" ").append(order.order() == SortOrder.DESC ? DESC : ASC);
                }

            }
        }
    }

    public record PreparedSqlImpl(String sql, List<?> args, List<Attribute> selected) implements PreparedSql {
    }
}
