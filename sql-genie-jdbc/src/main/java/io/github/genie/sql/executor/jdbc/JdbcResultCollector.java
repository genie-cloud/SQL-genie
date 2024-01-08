package io.github.genie.sql.executor.jdbc;

import io.github.genie.sql.api.Selection;
import io.github.genie.sql.builder.TypeCastUtil;
import io.github.genie.sql.builder.executor.ProjectionUtil;
import io.github.genie.sql.builder.meta.Attribute;
import lombok.SneakyThrows;
import org.jetbrains.annotations.NotNull;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.function.BiFunction;

import static io.github.genie.sql.api.Selection.MultiColumn;
import static io.github.genie.sql.api.Selection.SingleColumn;

@SuppressWarnings("PatternVariableCanBeUsed")
public class JdbcResultCollector implements JdbcQueryExecutor.ResultCollector {

    @Override
    public <R> R collect(@NotNull ResultSet resultSet,
                         @NotNull Selection selectClause,
                         @NotNull Class<?> fromType,
                         @NotNull List<? extends Attribute> attributes)
            throws SQLException {
        int columnsCount = resultSet.getMetaData().getColumnCount();
        int column = 0;
        if (selectClause instanceof MultiColumn) {
            MultiColumn multiColumn = (MultiColumn) selectClause;
            if (multiColumn.columns().size() != columnsCount) {
                throw new IllegalStateException();
            }
            Object[] row = new Object[columnsCount];
            while (column < columnsCount) {
                row[column++] = resultSet.getObject(column);
            }
            return TypeCastUtil.unsafeCast(row);
        } else if (selectClause instanceof SingleColumn) {
            SingleColumn singleColumn = (SingleColumn) selectClause;
            if (1 != columnsCount) {
                throw new IllegalStateException();
            }
            Object r = JdbcUtil.getValue(resultSet, 1, singleColumn.resultType());
            return TypeCastUtil.unsafeCast(r);
        } else {
            if (attributes.size() != columnsCount) {
                throw new IllegalStateException();
            }
            Class<?> resultType = selectClause.resultType();
            BiFunction<Integer, Class<?>, Object> extractor = getJdbcResultValueExtractor(resultSet);
            if (resultType.isInterface()) {
                return ProjectionUtil.getInterfaceResult(extractor, attributes, resultType);
            } else if (resultType.isRecord()) {
                return ProjectionUtil.getRecordResult(extractor, attributes, resultType);
            } else {
                return ProjectionUtil.getBeanResult(extractor, attributes, resultType);
            }
        }
    }

    @NotNull
    private static BiFunction<Integer, Class<?>, Object> getJdbcResultValueExtractor(@NotNull ResultSet resultSet) {
        // noinspection Convert2Lambda
        return new BiFunction<>() {
            @SneakyThrows
            @Override
            public Object apply(Integer index, Class<?> resultType) {
                return JdbcUtil.getValue(resultSet, 1 + index, resultType);
            }
        };
    }

}
