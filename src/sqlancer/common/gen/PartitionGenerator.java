package sqlancer.common.gen;

import sqlancer.common.ast.newast.Expression;
import sqlancer.common.schema.AbstractTableColumn;

public interface PartitionGenerator<E extends Expression<C>, C extends AbstractTableColumn<?, ?>> {

    E negatePredicate(E predicate);

    E isNull(E expr);
}
