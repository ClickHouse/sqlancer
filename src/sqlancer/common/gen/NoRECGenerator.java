package sqlancer.common.gen;

import java.util.List;

import sqlancer.common.ast.newast.Expression;
import sqlancer.common.ast.newast.Join;
import sqlancer.common.ast.newast.Select;
import sqlancer.common.schema.AbstractTable;
import sqlancer.common.schema.AbstractTableColumn;
import sqlancer.common.schema.AbstractTables;

public interface NoRECGenerator<S extends Select<J, E, T, C>, J extends Join<E, T, C>, E extends Expression<C>, T extends AbstractTable<C, ?, ?>, C extends AbstractTableColumn<?, ?>> {

    NoRECGenerator<S, J, E, T, C> setTablesAndColumns(AbstractTables<T, C> tables);

    E generateBooleanExpression();

    S generateSelect();

    List<J> getRandomJoinClauses();

    List<E> getTableRefs();

    String generateOptimizedQueryString(S select, E whereCondition, boolean shouldUseAggregate);

    String generateUnoptimizedQueryString(S select, E whereCondition);
}
