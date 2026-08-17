package sqlancer.clickhouse.oracle.cert;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import sqlancer.IgnoreMeException;
import sqlancer.Randomly;
import sqlancer.clickhouse.ClickHouseErrors;
import sqlancer.clickhouse.ClickHouseProvider.ClickHouseGlobalState;
import sqlancer.clickhouse.ClickHouseSchema.ClickHouseColumn;
import sqlancer.clickhouse.ClickHouseSchema.ClickHouseTable;
import sqlancer.clickhouse.ClickHouseType;
import sqlancer.clickhouse.ClickHouseType.Kind;
import sqlancer.clickhouse.ClickHouseType.LowCardinality;
import sqlancer.clickhouse.ClickHouseType.Nullable;
import sqlancer.clickhouse.ClickHouseType.Primitive;
import sqlancer.clickhouse.ClickHouseType.Unknown;
import sqlancer.clickhouse.ClickHouseVisitor;
import sqlancer.clickhouse.ast.ClickHouseBinaryLogicalOperation;
import sqlancer.clickhouse.ast.ClickHouseBinaryLogicalOperation.ClickHouseBinaryLogicalOperator;
import sqlancer.clickhouse.ast.ClickHouseColumnReference;
import sqlancer.clickhouse.ast.ClickHouseExpression;
import sqlancer.clickhouse.ast.ClickHouseSelect;
import sqlancer.clickhouse.ast.ClickHouseSelect.SelectType;
import sqlancer.clickhouse.ast.ClickHouseTableReference;
import sqlancer.clickhouse.gen.ClickHouseExpressionGenerator;
import sqlancer.common.DBMSCommon;
import sqlancer.common.oracle.CERTOracleBase;
import sqlancer.common.oracle.TestOracle;

public class ClickHouseCERTOracle extends CERTOracleBase<ClickHouseGlobalState>
        implements TestOracle<ClickHouseGlobalState> {

    private static final long TARGET_ROWS = 10_000L;
    private static final int PK_WEIGHT = 4;

    private ClickHouseExpressionGenerator gen;
    private ClickHouseSelect select;
    private List<ClickHouseColumnReference> columns;
    private List<ClickHouseColumnReference> pkColumns;
    private List<ClickHouseColumnReference> weightedColumns;

    public ClickHouseCERTOracle(ClickHouseGlobalState state) {
        super(state);
        ClickHouseErrors.addExpectedExpressionErrors(this.errors);
    }

    @Override
    public void check() throws SQLException {
        queryPlan1Sequences = new ArrayList<>();
        queryPlan2Sequences = new ArrayList<>();

        List<ClickHouseTable> tables = state.getSchema().getRandomTableNonEmptyTables().getTables().stream()
                .filter(t -> !t.isView()).collect(Collectors.toList());
        if (tables.isEmpty()) {
            throw new IgnoreMeException();
        }
        ClickHouseTable pivotTable = Randomly.fromList(tables);
        ensureLargeEnough(pivotTable);

        ClickHouseTableReference table = new ClickHouseTableReference(pivotTable, null);
        select = new ClickHouseSelect();
        select.setFromClause(table);
        columns = table.getColumnReferences();
        if (columns.isEmpty()) {
            throw new IgnoreMeException();
        }
        pkColumns = fetchPkColumns(pivotTable, columns);
        weightedColumns = buildWeightedColumns(columns, pkColumns);

        gen = new ClickHouseExpressionGenerator(state);
        gen.addColumns(weightedColumns);
        select.setFetchColumns(columns.stream().map(c -> (ClickHouseExpression) c).collect(Collectors.toList()));

        if (!pkColumns.isEmpty() && Randomly.getBooleanWithRatherLowProbability()) {
            ClickHouseColumnReference pk = Randomly.fromList(pkColumns);
            select.setFetchColumns(Collections.singletonList(pk));
            select.setGroupByClause(Collections.singletonList(pk));
        }
        if (Randomly.getBoolean()) {
            select.setWhereClause(gen.generateExpressionWithColumns(weightedColumns, 4));
        }

        String q1 = ClickHouseVisitor.asString(select);
        long card1 = explainEstimateRows(q1);
        if (card1 < 0) {
            throw new IgnoreMeException();
        }
        queryPlan1Sequences = explainPlanSequence(q1);

        int nrMutations = 1 + (int) Randomly.getNotCachedInteger(0, 3);
        for (int i = 0; i < nrMutations; i++) {
            boolean expectedIncrease = mutate(Mutator.JOIN, Mutator.GROUPBY, Mutator.LIMIT);
            if (expectedIncrease) {

                throw new IgnoreMeException();
            }
        }

        String q2 = ClickHouseVisitor.asString(select);
        long card2 = explainEstimateRows(q2);
        if (card2 < 0) {
            throw new IgnoreMeException();
        }
        queryPlan2Sequences = explainPlanSequence(q2);

        if (queryPlan1Sequences.isEmpty() || queryPlan2Sequences.isEmpty()) {
            return;
        }
        if (DBMSCommon.editDistance(queryPlan1Sequences, queryPlan2Sequences) > 1) {
            return;
        }

        if (card2 > card1) {
            throw new AssertionError(String.format(
                    "CERT: more-restrictive query has higher estimated cardinality (%d > %d)%n  Q1: %s%n  Q2: %s",
                    card2, card1, q1, q2));
        }
    }

    @Override
    protected boolean mutateWhere() {
        ClickHouseExpression extra = gen.generateExpressionWithColumns(weightedColumns, 3);
        ClickHouseExpression w = select.getWhereClause();
        if (w == null) {
            select.setWhereClause(extra);
        } else {
            select.setWhereClause(new ClickHouseBinaryLogicalOperation(w, extra, ClickHouseBinaryLogicalOperator.AND));
        }
        return false;
    }

    @Override
    protected boolean mutateAnd() {
        return mutateWhere();
    }

    @Override
    protected boolean mutateOr() {
        ClickHouseExpression w = select.getWhereClause();
        if (w instanceof ClickHouseBinaryLogicalOperation) {
            ClickHouseBinaryLogicalOperation bl = (ClickHouseBinaryLogicalOperation) w;
            if (bl.getOp() == ClickHouseBinaryLogicalOperator.OR) {
                select.setWhereClause(Randomly.getBoolean() ? bl.getLeft() : bl.getRight());
                return false;
            }
        }
        return mutateWhere();
    }

    @Override
    protected boolean mutateDistinct() {
        if (select.getFromOptions() == SelectType.DISTINCT) {

            return mutateWhere();
        }
        select.setSelectType(SelectType.DISTINCT);
        return false;
    }

    @Override
    protected boolean mutateHaving() {
        if (select.getGroupByClause().isEmpty()) {
            return mutateWhere();
        }
        List<ClickHouseColumnReference> bias = pkColumns.isEmpty() ? weightedColumns : pkColumns;
        ClickHouseExpression extra = gen.generateExpressionWithColumns(bias, 3);
        ClickHouseExpression h = select.getHavingClause();
        if (h == null) {
            select.setHavingClause(extra);
        } else {
            select.setHavingClause(new ClickHouseBinaryLogicalOperation(h, extra, ClickHouseBinaryLogicalOperator.AND));
        }
        return false;
    }

    private void ensureLargeEnough(ClickHouseTable table) {
        long rows = countRows(table);
        if (rows < 0 || rows >= TARGET_ROWS) {
            return;
        }
        long toInsert = TARGET_ROWS - rows;
        StringBuilder sb = new StringBuilder("INSERT INTO ");
        sb.append(quote(table.getName())).append(" SELECT ");
        boolean first = true;
        for (ClickHouseColumn c : table.getColumns()) {
            if (c.isAlias() || c.isMaterialized()) {
                continue;
            }
            if (!first) {
                sb.append(", ");
            }
            first = false;
            sb.append(generatorExprFor(c.getType().getTypeTerm())).append(" AS ").append(quote(c.getName()));
        }
        sb.append(" FROM numbers(").append(toInsert).append(")");
        if (state.getOptions().logEachSelect()) {

            state.getLogger().writeCurrent(sb.toString());
            state.getState().logStatement(sb.toString());
        }
        try (Statement s = state.getConnection().createStatement()) {
            s.execute(sb.toString());
        } catch (SQLException ignored) {

        }
    }

    static String generatorExprFor(ClickHouseType term) {
        if (term instanceof Unknown) {
            throw new IgnoreMeException();
        }
        if (term instanceof Nullable n) {
            String inner = generatorExprFor(n.inner());
            return "if(rand() % 10 = 0, NULL, " + inner + ")";
        }
        if (term instanceof LowCardinality lc) {
            return generatorExprFor(lc.inner());
        }
        if (term instanceof Primitive p) {
            return generatorExprForPrimitive(p.kind());
        }
        throw new IgnoreMeException();
    }

    private static String generatorExprForPrimitive(Kind kind) {

        switch (kind) {
        case String:
            return "toString(number)";
        case Float32:
            return "toFloat32(number)";
        case Float64:
            return "toFloat64(number)";
        case Bool:
            return "toBool(number % 2)";

        case Int8:
            return "toInt8(toInt32(number % 200) - 100)";
        case Int16:
            return "toInt16(toInt32(number % 60000) - 30000)";
        case Int32:
            return "toInt32(toInt64(number) - 25000)";
        case Int64:
            return "toInt64(toInt64(number) - 25000)";
        case Int128:
            return "toInt128(toInt64(number) - 25000)";
        case Int256:
            return "toInt256(toInt64(number) - 25000)";

        case UInt8:
            return "toUInt8(number % 256)";
        case UInt16:
            return "toUInt16(number % 65536)";
        case UInt32:
            return "toUInt32(number)";
        case UInt64:
            return "toUInt64(number)";
        case UInt128:
            return "toUInt128(number)";
        case UInt256:
            return "toUInt256(number)";

        case Date:
            return "toDate(toUInt32(number % 50000))";

        case Date32:
            return "toDate32(toInt32(number))";

        case DateTime:
            return "toDateTime(toUInt32(number))";

        case UUID:
            return "toUUID(concat('00000000-0000-0000-0000-', leftPad(toString(number), 12, '0')))";

        case IPv4:
            return "toIPv4('192.0.2.1')";
        case IPv6:
            return "toIPv6('2001:db8::1')";
        default:

            throw new IgnoreMeException();
        }
    }

    private long countRows(ClickHouseTable table) {
        try (Statement s = state.getConnection().createStatement();
                ResultSet rs = s.executeQuery("SELECT count() FROM " + quote(table.getName()))) {
            return rs.next() ? rs.getLong(1) : -1;
        } catch (SQLException ignored) {
            return -1;
        }
    }

    private List<ClickHouseColumnReference> fetchPkColumns(ClickHouseTable table, List<ClickHouseColumnReference> all) {
        Set<String> pkNames = new LinkedHashSet<>();
        String sql = String.format(
                "SELECT name FROM system.columns WHERE database = '%s' AND table = '%s' AND is_in_primary_key = 1",
                state.getDatabaseName().replace("'", "''"), table.getName().replace("'", "''"));
        try (Statement s = state.getConnection().createStatement(); ResultSet rs = s.executeQuery(sql)) {
            while (rs.next()) {
                pkNames.add(rs.getString(1));
            }
        } catch (SQLException ignored) {
            return Collections.emptyList();
        }
        if (pkNames.isEmpty()) {
            return Collections.emptyList();
        }
        return all.stream().filter(c -> pkNames.contains(c.getColumn().getName())).collect(Collectors.toList());
    }

    private static List<ClickHouseColumnReference> buildWeightedColumns(List<ClickHouseColumnReference> all,
            List<ClickHouseColumnReference> pk) {
        if (pk.isEmpty()) {
            return all;
        }
        List<ClickHouseColumnReference> weighted = new ArrayList<>(all);
        for (int i = 0; i < PK_WEIGHT - 1; i++) {
            weighted.addAll(pk);
        }
        return weighted;
    }

    private long explainEstimateRows(String query) {
        try (Statement s = state.getConnection().createStatement();
                ResultSet rs = s.executeQuery("EXPLAIN ESTIMATE " + query)) {
            long total = 0;
            boolean any = false;
            while (rs.next()) {
                any = true;
                total += rs.getLong("rows");
            }
            return any ? total : -1;
        } catch (SQLException ignored) {

            return -1;
        }
    }

    private List<String> explainPlanSequence(String query) {
        List<String> plan = new ArrayList<>();
        try (Statement s = state.getConnection().createStatement();
                ResultSet rs = s.executeQuery("EXPLAIN PLAN " + query)) {
            while (rs.next()) {
                String line = rs.getString(1);
                if (line == null) {
                    continue;
                }
                String op = line.trim().split("[\\s(]", 2)[0];
                if (!op.isEmpty()) {
                    plan.add(op);
                }
            }
        } catch (SQLException ignored) {

        }
        return plan;
    }

    private static String quote(String identifier) {
        return "`" + identifier.replace("`", "``") + "`";
    }
}
