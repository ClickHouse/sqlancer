package sqlancer.clickhouse.ast;

/**
 * Set-operation node combining two query expressions ({@link ClickHouseSelect} or a nested
 * {@link ClickHouseSetOperation}) with an explicit {@code UNION}/{@code INTERSECT}/{@code EXCEPT} keyword.
 *
 * <p>
 * Tree shape determines grouping at render time -- the visitor mechanically emits {@code left OP right}; if precedence
 * matters, callers must construct the tree to reflect the desired grouping. ClickHouse's parser follows ANSI precedence
 * ({@code INTERSECT > UNION = EXCEPT}); this node does not enforce it.
 * </p>
 *
 * <p>
 * The six {@link SetOpKind} values encode the explicit operator keyword that gets emitted. Bare {@code INTERSECT} /
 * {@code EXCEPT} (without an {@code ALL}/{@code DISTINCT} qualifier) deliberately are not represented: their semantics
 * are governed by {@code intersect_default_mode} / {@code except_default_mode} session settings, which is exactly the
 * version-dependent surface this AST node is designed to dodge.
 * </p>
 */
public class ClickHouseSetOperation extends ClickHouseExpression {

    public enum SetOpKind {
        UNION_ALL("UNION ALL"), UNION_DISTINCT("UNION DISTINCT"), INTERSECT_ALL("INTERSECT ALL"),
        INTERSECT_DISTINCT("INTERSECT DISTINCT"), EXCEPT_ALL("EXCEPT ALL"), EXCEPT_DISTINCT("EXCEPT DISTINCT");

        private final String keyword;

        SetOpKind(String keyword) {
            this.keyword = keyword;
        }

        public String getKeyword() {
            return keyword;
        }
    }

    private final ClickHouseExpression left;
    private final ClickHouseExpression right;
    private final SetOpKind op;

    public ClickHouseSetOperation(ClickHouseExpression left, SetOpKind op, ClickHouseExpression right) {
        this.left = left;
        this.op = op;
        this.right = right;
    }

    public ClickHouseExpression getLeft() {
        return left;
    }

    public ClickHouseExpression getRight() {
        return right;
    }

    public SetOpKind getOp() {
        return op;
    }
}
