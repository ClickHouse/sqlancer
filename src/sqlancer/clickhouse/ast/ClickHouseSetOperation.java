package sqlancer.clickhouse.ast;

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
