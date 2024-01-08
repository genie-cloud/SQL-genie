package io.github.genie.sql.builder;

import io.github.genie.sql.api.Query;
import io.github.genie.sql.api.QueryExecutor;

public interface AbstractQueryExecutor extends QueryExecutor {
    default Query createQuery() {
        return new Query() {
            @Override
            public <T> Select<T> from(Class<T> type) {
                return new QueryBuilder<>(AbstractQueryExecutor.this, type);
            }
        };
    }

    default Query createQuery(QueryStructurePostProcessor structurePostProcessor) {
        return new Query() {
            @Override
            public <T> Select<T> from(Class<T> type) {
                return new QueryBuilder<>(AbstractQueryExecutor.this, type, structurePostProcessor);
            }
        };
    }
}
