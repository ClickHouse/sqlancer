package sqlancer.clickhouse.oracle.dict;

import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import sqlancer.ComparatorHelper;
import sqlancer.IgnoreMeException;
import sqlancer.Randomly;
import sqlancer.clickhouse.ClickHouseErrors;
import sqlancer.clickhouse.ClickHouseProvider.ClickHouseGlobalState;
import sqlancer.clickhouse.ClickHouseSchema.ClickHouseColumn;
import sqlancer.clickhouse.ClickHouseSchema.ClickHouseTable;
import sqlancer.common.oracle.TestOracle;
import sqlancer.common.query.ExpectedErrors;

/**
 * Dictionary differential oracle. Asserts:
 * <pre>
 *   SELECT dictGet('d', 'col', t.k) FROM t  ==  SELECT src.col FROM t LEFT JOIN src ON t.k = src.k
 * </pre>
 * for a transient CLICKHOUSE-sourced dictionary {@code d} mapping {@code src.k -> src.col}.
 * Creates the dictionary in setup, runs both queries, drops the dictionary in teardown.
 *
 * <p>Workstream 14 of the 2026-05-27 coverage expansion plan. Picks a source table with an
 * integer-typed column to serve as the key; the dictionary's value column is any other column.
 * LIFETIME is pinned to 0 (static) to make replays deterministic; SEMR-style randomization of
 * LIFETIME is left for a follow-up sweep.
 */
public class ClickHouseDictGetVsJoinOracle implements TestOracle<ClickHouseGlobalState> {

    private static final AtomicLong DICT_COUNTER = new AtomicLong();

    private final ClickHouseGlobalState state;
    private final ExpectedErrors errors;

    public ClickHouseDictGetVsJoinOracle(ClickHouseGlobalState state) {
        this.state = state;
        this.errors = ExpectedErrors.newErrors().with(ClickHouseErrors.getExpectedExpressionErrors()).build();
    }

    @Override
    public void check() throws SQLException {
        List<ClickHouseTable> tables = state.getSchema().getDatabaseTables().stream()
                .filter(t -> !t.isView()).collect(Collectors.toList());
        if (tables.isEmpty()) {
            throw new IgnoreMeException();
        }
        ClickHouseTable srcTable = Randomly.fromList(tables);
        // Find a UInt-like key column and a different value column.
        List<ClickHouseColumn> intCols = srcTable.getColumns().stream().filter(c -> {
            com.clickhouse.data.ClickHouseDataType t = c.getType().getType();
            return t == com.clickhouse.data.ClickHouseDataType.UInt32
                    || t == com.clickhouse.data.ClickHouseDataType.UInt64
                    || t == com.clickhouse.data.ClickHouseDataType.Int32
                    || t == com.clickhouse.data.ClickHouseDataType.Int64;
        }).collect(Collectors.toList());
        if (intCols.isEmpty() || srcTable.getColumns().size() < 2) {
            throw new IgnoreMeException();
        }
        ClickHouseColumn keyCol = Randomly.fromList(intCols);
        ClickHouseColumn valCol = Randomly.fromList(srcTable.getColumns().stream()
                .filter(c -> c != keyCol).collect(Collectors.toList()));
        String dictName = "d" + DICT_COUNTER.incrementAndGet();
        String fqSrc = state.getDatabaseName() + "." + srcTable.getName();
        String fqDict = state.getDatabaseName() + "." + dictName;

        String createDict = String.format(
                "CREATE DICTIONARY %s (%s UInt64, %s String) PRIMARY KEY %s "
                        + "SOURCE(CLICKHOUSE(TABLE '%s' DB '%s')) LIFETIME(0) LAYOUT(HASHED())",
                fqDict, keyCol.getName(), valCol.getName(), keyCol.getName(), srcTable.getName(),
                state.getDatabaseName());

        try (Statement s = state.getConnection().createStatement()) {
            s.execute(createDict);
        } catch (SQLException e) {
            if (errors.errorIsExpected(e.getMessage()) || (e.getMessage() != null
                    && e.getMessage().contains("DICTIONARY"))) {
                throw new IgnoreMeException();
            }
            throw e;
        }

        try {
            String lhs = String.format(
                    "SELECT dictGet('%s', '%s', toUInt64(%s)) FROM %s ORDER BY %s",
                    fqDict, valCol.getName(), keyCol.getName(), fqSrc, keyCol.getName());
            String rhs = String.format(
                    "SELECT src.%s FROM %s t LEFT JOIN %s src ON t.%s = src.%s ORDER BY src.%s",
                    valCol.getName(), fqSrc, fqSrc, keyCol.getName(), keyCol.getName(), keyCol.getName());
            List<String> lhsResult = ComparatorHelper.getResultSetFirstColumnAsString(lhs, errors, state);
            List<String> rhsResult = ComparatorHelper.getResultSetFirstColumnAsString(rhs, errors, state);
            ComparatorHelper.assumeResultSetsAreEqual(lhsResult, rhsResult, lhs,
                    java.util.Collections.singletonList(rhs), state, ComparatorHelper.ComparisonMode.MULTISET);
        } finally {
            try (Statement s = state.getConnection().createStatement()) {
                s.execute("DROP DICTIONARY IF EXISTS " + fqDict);
            } catch (SQLException ignored) {
                // teardown failure is not a bug
            }
        }
    }
}
