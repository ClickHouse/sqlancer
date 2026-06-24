package sqlancer.mysql.ast;

import java.util.List;

public class MySQLAggregate implements MySQLExpression {

    public enum MySQLAggregateFunction {

        COUNT("COUNT", null, false), COUNT_DISTINCT("COUNT", "DISTINCT", true),

        SUM("SUM", null, false), SUM_DISTINCT("SUM", "DISTINCT", false),

        MIN("MIN", null, false), MIN_DISTINCT("MIN", "DISTINCT", false),

        MAX("MAX", null, false), MAX_DISTINCT("MAX", "DISTINCT", false);

        private final String name;
        private final String option;
        private final boolean isVariadic;

        MySQLAggregateFunction(String name, String option, boolean isVariadic) {
            this.name = name;
            this.option = option;
            this.isVariadic = isVariadic;
        }

        public String getName() {
            return this.name;
        }

        public String getOption() {
            return option;
        }

        public boolean isVariadic() {
            return this.isVariadic;
        }
    }

    private final List<MySQLExpression> exprs;
    private final MySQLAggregateFunction func;

    public MySQLAggregate(List<MySQLExpression> exprs, MySQLAggregateFunction func) {
        this.exprs = exprs;
        this.func = func;
    }

    public List<MySQLExpression> getExprs() {
        return exprs;
    }

    public MySQLAggregateFunction getFunc() {
        return func;
    }
}
