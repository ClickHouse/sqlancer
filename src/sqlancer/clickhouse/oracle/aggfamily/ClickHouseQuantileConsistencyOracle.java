package sqlancer.clickhouse.oracle.aggfamily;

import java.math.BigInteger;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import sqlancer.IgnoreMeException;
import sqlancer.Randomly;
import sqlancer.clickhouse.ClickHouseErrors;
import sqlancer.clickhouse.ClickHouseProvider.ClickHouseGlobalState;
import sqlancer.clickhouse.ClickHouseSchema.ClickHouseColumn;
import sqlancer.clickhouse.ClickHouseSchema.ClickHouseTable;
import sqlancer.clickhouse.ClickHouseType;
import sqlancer.clickhouse.ClickHouseType.Kind;
import sqlancer.clickhouse.ClickHouseType.Nullable;
import sqlancer.clickhouse.ClickHouseType.Primitive;
import sqlancer.common.oracle.TestOracle;
import sqlancer.common.query.ExpectedErrors;

public class ClickHouseQuantileConsistencyOracle implements TestOracle<ClickHouseGlobalState> {

    private final ClickHouseGlobalState state;
    private final ExpectedErrors errors = new ExpectedErrors();

    public ClickHouseQuantileConsistencyOracle(ClickHouseGlobalState state) {
        this.state = state;
        ClickHouseErrors.addExpectedExpressionErrors(errors);
        ClickHouseErrors.addSessionSettingsErrors(errors);
    }

    @Override
    public void check() throws SQLException {
        if (!state.getClickHouseOptions().quantileConsistencyOracle) {
            throw new IgnoreMeException();
        }
        List<ClickHouseTable> tables = state.getSchema().getRandomTableNonEmptyTables().getTables().stream()
                .filter(t -> !t.isView()).collect(Collectors.toList());
        if (tables.isEmpty()) {
            throw new IgnoreMeException();
        }
        ClickHouseTable table = Randomly.fromList(tables);
        List<ClickHouseColumn> integerColumns = table.getColumns().stream()
                .filter(c -> !c.isAlias() && !c.isMaterialized()).filter(ClickHouseQuantileConsistencyOracle::isIntegerColumn)
                .collect(Collectors.toList());
        if (integerColumns.isEmpty()) {
            throw new IgnoreMeException();
        }
        ClickHouseColumn column = Randomly.fromList(integerColumns);
        String c = quote(column.getName());
        String tableQ = quote(table.getName());

        String query = "SELECT toString(quantileExact(0.5)(" + c + ")) AS a, toString(medianExact(" + c
                + ")) AS b, toString(quantilesExact(0.25, 0.75)(" + c + ")[1]) AS d, toString(quantileExact(0.25)(" + c
                + ")) AS e, toString(quantileExact(0.1)(" + c + ")) AS f, toString(quantileExact(0.9)(" + c
                + ")) AS g, toString(quantileExactLow(0.5)(" + c + ")) AS lo, toString(quantileExactHigh(0.5)(" + c
                + ")) AS hi, toString(quantileExact(0.5)(" + c + ")) AS mid FROM " + tableQ;

        if (state.getOptions().logEachSelect()) {
            state.getLogger().writeCurrent(query);
        }

        List<String> row = new ArrayList<>();
        try (Statement s = state.getConnection().createStatement(); ResultSet rs = s.executeQuery(query)) {
            if (!rs.next()) {
                throw new IgnoreMeException();
            }
            for (int i = 1; i <= 9; i++) {
                String v = rs.getString(i);
                if (rs.wasNull() || v == null) {
                    throw new IgnoreMeException();
                }
                row.add(v);
            }
        } catch (SQLException ex) {
            throw maybeIgnore(ex);
        }

        String median = row.get(0);
        String medianExact = row.get(1);
        String quantilesElem = row.get(2);
        String q25 = row.get(3);
        String q10 = row.get(4);
        String q90 = row.get(5);
        String low = row.get(6);
        String high = row.get(7);
        String mid = row.get(8);

        if (!median.equals(medianExact)) {
            throw new AssertionError(String.format(
                    "quantile-family mismatch: quantileExact(0.5)=%s but medianExact=%s on column %s. Q: %s", median,
                    medianExact, c, query));
        }
        if (!quantilesElem.equals(q25)) {
            throw new AssertionError(String.format(
                    "quantile-family mismatch: quantilesExact(0.25,0.75)[1]=%s but quantileExact(0.25)=%s on column %s. Q: %s",
                    quantilesElem, q25, c, query));
        }

        BigInteger lowQuantile = parseInteger(q10);
        BigInteger highQuantile = parseInteger(q90);
        BigInteger loValue = parseInteger(low);
        BigInteger midValue = parseInteger(mid);
        BigInteger hiValue = parseInteger(high);
        if (lowQuantile == null || highQuantile == null || loValue == null || midValue == null || hiValue == null) {
            throw new IgnoreMeException();
        }

        if (lowQuantile.compareTo(highQuantile) > 0) {
            throw new AssertionError(String.format(
                    "quantile-family monotonicity violated: quantileExact(0.1)=%s > quantileExact(0.9)=%s on column %s. Q: %s",
                    q10, q90, c, query));
        }
        if (loValue.compareTo(midValue) > 0 || midValue.compareTo(hiValue) > 0) {
            throw new AssertionError(String.format(
                    "quantile-family ordering violated: quantileExactLow(0.5)=%s, quantileExact(0.5)=%s, quantileExactHigh(0.5)=%s on column %s. Q: %s",
                    low, mid, high, c, query));
        }
    }

    static boolean isIntegerColumn(ClickHouseColumn column) {
        ClickHouseType term = column.getType().getTypeTerm();
        if (term instanceof Nullable nullable) {
            term = nullable.inner();
        }
        if (term instanceof Primitive primitive) {
            return isIntegerKind(primitive.kind());
        }
        return false;
    }

    static boolean isIntegerKind(Kind kind) {
        switch (kind) {
        case Int8:
        case Int16:
        case Int32:
        case Int64:
        case Int128:
        case Int256:
        case UInt8:
        case UInt16:
        case UInt32:
        case UInt64:
        case UInt128:
        case UInt256:
            return true;
        default:
            return false;
        }
    }

    private static BigInteger parseInteger(String value) {
        try {
            return new BigInteger(value.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private SQLException maybeIgnore(SQLException ex) {
        if (ex.getMessage() != null && errors.errorIsExpected(ex.getMessage())) {
            throw new IgnoreMeException();
        }
        return ex;
    }

    static String quote(String identifier) {
        return "`" + identifier.replace("`", "``") + "`";
    }
}
