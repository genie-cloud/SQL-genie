package io.github.genie.sql.core;


import io.github.genie.sql.core.Expression.Meta;
import io.github.genie.sql.core.Expression.Paths;

import java.io.Serializable;
import java.util.List;

public interface QueryMetadata extends Serializable {

    SelectClause select();

    Class<?> from();

    Meta where();

    List<? extends Meta> groupBy();

    List<? extends Ordering<?>> orderBy();

    Meta having();

    Integer offset();

    Integer limit();

    LockModeType lockType();

    List<? extends Paths> fetch();
}
