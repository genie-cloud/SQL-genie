package io.github.genie.sql.builder;

import io.github.genie.sql.api.Column;
import io.github.genie.sql.api.Constant;
import io.github.genie.sql.api.Expression;
import io.github.genie.sql.api.ExpressionHolder;
import io.github.genie.sql.api.ExpressionOperator.PathOperator;
import io.github.genie.sql.api.ExpressionOperator.Predicate;
import io.github.genie.sql.api.Lists;
import io.github.genie.sql.api.Operation;
import io.github.genie.sql.api.Operator;
import io.github.genie.sql.api.Path;
import io.github.genie.sql.builder.QueryStructures.ColumnMeta;
import io.github.genie.sql.builder.QueryStructures.ConstantMeta;
import io.github.genie.sql.builder.QueryStructures.OperationMeta;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@SuppressWarnings("PatternVariableCanBeUsed")
public interface Expressions {

    Expression TRUE = of(true);

    static boolean isTrue(Expression expression) {
        return expression instanceof Constant
               && Boolean.TRUE.equals(((Constant) expression).value());
    }

    static Expression of(ExpressionHolder<?, ?> expression) {
        return expression.expression();
    }

    static Expression of(Object value) {
        if (value instanceof ExpressionHolder<?, ?>) {
            return ((ExpressionHolder<?, ?>) value).expression();
        } else if (value instanceof Path<?, ?>) {
            return of((Path<?, ?>) value);
        }
        return new ConstantMeta(value);
    }

    static Expression of(Expression value) {
        return value;
    }

    static Column of(Path<?, ?> path) {
        String property = columnName(path);
        return column(property);
    }

    static String columnName(Path<?, ?> path) {
        return Util.getPropertyName(path);
    }

    static Column column(String path) {
        List<String> paths = new ArrayList<>(1);
        paths.add(path);
        return column(paths);
    }

    static Column column(List<String> paths) {
        Objects.requireNonNull(paths);
        if (paths.getClass() != ArrayList.class) {
            paths = new ArrayList<>(paths);
        }
        return new ColumnMeta(paths);
    }

    static Expression operate(Expression l, Operator o, Expression r) {
        return operate(l, o, Lists.of(r));
    }

    static Expression operate(Expression l, Operator o) {
        return operate(l, o, Lists.of());
    }

    static Expression operate(Expression l, Operator o, List<? extends Expression> r) {
        if (o == Operator.NOT
            && l instanceof Operation
            && ((Operation) l).operator() == Operator.NOT) {
            Operation operation = (Operation) l;
            return operation.operand();
        }
        if (o.isMultivalued() && l instanceof Operation && ((Operation) l).operator() == o) {
            Operation lo = (Operation) l;
            List<Expression> args = Util.concat(lo.args(), r);
            return new OperationMeta(lo.operand(), o, args);
        }
        return new OperationMeta(l, o, r);
    }

    static <T> List<PathOperator<T, ?, Predicate<T>>> toExpressionList(Collection<Path<T, ?>> paths) {
        return paths.stream()
                .<PathOperator<T, ?, Predicate<T>>>map(Q::get)
                .collect(Collectors.toList());
    }

    static Column concat(Column join, String path) {
        return column(Util.concat(join.paths(), path));
    }

    static Column concat(Column join, Path<?, ?> path) {
        return column(Util.concat(join.paths(), columnName(path)));
    }

}
