package sqlancer.clickhouse.gen;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import com.clickhouse.data.ClickHouseDataType;

import sqlancer.Randomly;
import sqlancer.clickhouse.ClickHouseErrors;
import sqlancer.clickhouse.ClickHouseProvider;
import sqlancer.clickhouse.ClickHouseSchema;
import sqlancer.clickhouse.ClickHouseToStringVisitor;
import sqlancer.clickhouse.ast.ClickHouseColumnReference;
import sqlancer.clickhouse.ast.ClickHouseExpression;
import sqlancer.common.query.ExpectedErrors;
import sqlancer.common.query.SQLQueryAdapter;
import sqlancer.common.visitor.BinaryOperation;
import sqlancer.common.visitor.UnaryOperation;

public class ClickHouseTableGenerator {

    private enum ClickHouseEngine {
        // TinyLog, StripeLog,
        Log, Memory, MergeTree
    }

    private final StringBuilder sb = new StringBuilder();
    private final String tableName;
    private int columnId;
    private final List<String> columnNames = new ArrayList<>();
    private final List<ClickHouseSchema.ClickHouseColumn> columns = new ArrayList<>();
    private final ClickHouseProvider.ClickHouseGlobalState globalState;

    public ClickHouseTableGenerator(String tableName, ClickHouseProvider.ClickHouseGlobalState globalState) {
        this.tableName = tableName;
        this.globalState = globalState;
    }

    public static SQLQueryAdapter createTableStatement(String tableName,
            ClickHouseProvider.ClickHouseGlobalState globalState) {
        ClickHouseTableGenerator chTableGenerator = new ClickHouseTableGenerator(tableName, globalState);
        chTableGenerator.start();
        ExpectedErrors errors = new ExpectedErrors();
        ClickHouseErrors.addExpectedExpressionErrors(errors);
        return new SQLQueryAdapter(chTableGenerator.sb.toString(), errors, true);
    }

    public void start() {
        ClickHouseEngine engine = Randomly.fromOptions(ClickHouseEngine.values());
        ClickHouseExpressionGenerator gen = new ClickHouseExpressionGenerator(globalState).allowAggregates(false);
        sb.append("CREATE ");
        sb.append("TABLE ");
        if (Randomly.getBoolean()) {
            sb.append("IF NOT EXISTS ");
        }
        sb.append(this.globalState.getDatabaseName());
        sb.append(".");
        sb.append(this.tableName);
        sb.append(" (");
        int nrColumns = 1 + Randomly.smallNumber();
        for (int i = 0; i < nrColumns; i++) {
            columns.add(ClickHouseSchema.ClickHouseColumn.createDummy(ClickHouseCommon.createColumnName(i), null,
                    globalState));
        }
        for (int i = 0; i < nrColumns; i++) {
            if (i != 0) {
                sb.append(", ");
            }
            String columnName = ClickHouseCommon.createColumnName(columnId);
            ClickHouseColumnBuilder columnBuilder = new ClickHouseColumnBuilder();
            sb.append(columnBuilder.createColumn(columnName, globalState, columns));
            columnNames.add(columnName);
            columnId++;
        }
        if (Randomly.getBooleanWithSmallProbability()) {
            for (int i = 0; i < Randomly.smallNumber(); i++) {
                addColumnsConstraint(gen);
            }
        }
        sb.append(") ENGINE = ");
        sb.append(engine);
        sb.append("(");
        sb.append(") ");
        if (engine == ClickHouseEngine.MergeTree) {
            Supplier<ClickHouseExpression> exprFactory = () -> gen.generateExpressionWithColumns(
                    columns.stream().map(c -> c.asColumnReference(null)).collect(Collectors.toList()), 3);

            if (Randomly.getBoolean()) {
                ClickHouseExpression expr = generateValidated(exprFactory, ClickHouseTableGenerator::isValidOrderBy);
                if (expr != null) {
                    sb.append(" ORDER BY ");
                    sb.append(ClickHouseToStringVisitor.asString(expr));
                } else {
                    sb.append(" ORDER BY tuple() ");
                }
            } else {
                sb.append(" ORDER BY tuple() ");
            }

            if (Randomly.getBoolean()) {
                ClickHouseExpression expr = generateValidated(exprFactory,
                        ClickHouseTableGenerator::isValidPartitionBy);
                if (expr != null) {
                    sb.append(" PARTITION BY ");
                    sb.append(ClickHouseToStringVisitor.asString(expr));
                }
            }
            if (Randomly.getBoolean()) {
                ClickHouseExpression expr = generateValidated(exprFactory, ClickHouseTableGenerator::isValidSampleBy);
                if (expr != null) {
                    sb.append(" SAMPLE BY ");
                    sb.append(ClickHouseToStringVisitor.asString(expr));
                }
            }
            // Suppress index sanity checks https://github.com/sqlancer/sqlancer/issues/788; permit
            // Nullable columns in ORDER BY / PARTITION BY / SAMPLE BY -- otherwise ClickHouse
            // rejects them with ILLEGAL_COLUMN when the v1 type flags emit Nullable columns.
            sb.append(" SETTINGS allow_suspicious_indices=1, allow_nullable_key=1");
            // TODO: PRIMARY KEY
        }

    }

    private static final int CLAUSE_VALIDATION_RETRY_LIMIT = 5;

    // Generate an expression and run it through the supplied validator; retry up to
    // CLAUSE_VALIDATION_RETRY_LIMIT times. Returns null when no valid expression was produced --
    // the caller drops the clause rather than emitting one that ClickHouse will reject.
    private static ClickHouseExpression generateValidated(Supplier<ClickHouseExpression> factory,
            Predicate<ClickHouseExpression> validator) {
        for (int attempt = 0; attempt < CLAUSE_VALIDATION_RETRY_LIMIT; attempt++) {
            ClickHouseExpression expr = factory.get();
            if (validator.test(expr)) {
                return expr;
            }
        }
        return null;
    }

    // ORDER BY must reference at least one column -- "Sorting key cannot contain constants".
    static boolean isValidOrderBy(ClickHouseExpression expr) {
        return hasColumnReference(expr);
    }

    // PARTITION BY rejects float keys ("Floating point partition key is not supported") and
    // all-constant expressions ("Partition key cannot contain constants").
    static boolean isValidPartitionBy(ClickHouseExpression expr) {
        return hasColumnReference(expr) && !referencesFloatColumn(expr);
    }

    // SAMPLE BY must reference a column (the actual primary-key check is server-side).
    static boolean isValidSampleBy(ClickHouseExpression expr) {
        return hasColumnReference(expr);
    }

    private static boolean hasColumnReference(ClickHouseExpression expr) {
        if (expr instanceof ClickHouseColumnReference) {
            return true;
        }
        if (expr instanceof BinaryOperation<?> bo) {
            return hasColumnReference((ClickHouseExpression) bo.getLeft())
                    || hasColumnReference((ClickHouseExpression) bo.getRight());
        }
        if (expr instanceof UnaryOperation<?> uo) {
            return hasColumnReference((ClickHouseExpression) uo.getExpression());
        }
        return false;
    }

    private static boolean referencesFloatColumn(ClickHouseExpression expr) {
        if (expr instanceof ClickHouseColumnReference cr) {
            ClickHouseDataType t = cr.getColumn().getType().getType();
            return t == ClickHouseDataType.Float32 || t == ClickHouseDataType.Float64;
        }
        if (expr instanceof BinaryOperation<?> bo) {
            return referencesFloatColumn((ClickHouseExpression) bo.getLeft())
                    || referencesFloatColumn((ClickHouseExpression) bo.getRight());
        }
        if (expr instanceof UnaryOperation<?> uo) {
            return referencesFloatColumn((ClickHouseExpression) uo.getExpression());
        }
        return false;
    }

    private void addColumnsConstraint(ClickHouseExpressionGenerator gen) {
        for (int i = 0; i < Randomly.smallNumber() + 1; i++) {
            sb.append(",");
            sb.append(" CONSTRAINT ");
            sb.append(ClickHouseCommon.createConstraintName(i));
            sb.append(" CHECK ");
            ClickHouseExpression expr = gen.generateExpressionWithColumns(
                    columns.stream().map(c -> c.asColumnReference(null)).collect(Collectors.toList()), 2);
            sb.append(ClickHouseToStringVisitor.asString(expr));
        }
    }
}
