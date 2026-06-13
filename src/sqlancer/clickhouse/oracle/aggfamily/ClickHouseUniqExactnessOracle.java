package sqlancer.clickhouse.oracle.aggfamily;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.stream.Collectors;

import com.clickhouse.data.ClickHouseDataType;

import sqlancer.IgnoreMeException;
import sqlancer.Randomly;
import sqlancer.clickhouse.ClickHouseErrors;
import sqlancer.clickhouse.ClickHouseProvider.ClickHouseGlobalState;
import sqlancer.clickhouse.ClickHouseType;
import sqlancer.clickhouse.ClickHouseSchema.ClickHouseColumn;
import sqlancer.clickhouse.ClickHouseSchema.ClickHouseTable;
import sqlancer.common.oracle.TestOracle;
import sqlancer.common.query.ExpectedErrors;

public class ClickHouseUniqExactnessOracle implements TestOracle<ClickHouseGlobalState> {

    private final ClickHouseGlobalState state;
    private final ExpectedErrors errors = new ExpectedErrors();

    public ClickHouseUniqExactnessOracle(ClickHouseGlobalState state) {
        this.state = state;
        ClickHouseErrors.addExpectedExpressionErrors(errors);
        ClickHouseErrors.addSessionSettingsErrors(errors);
        errors.add("UNKNOWN_TABLE");
        errors.add("Unknown table expression identifier");
        errors.add("(MEMORY_LIMIT_EXCEEDED)");
        errors.add("memory limit exceeded");
        errors.add("TIMEOUT_EXCEEDED");
        errors.add("Timeout exceeded");
        errors.add("Limit for result exceeded");
        errors.add("TOO_MANY_ROWS_OR_BYTES");
    }

    @Override
    public void check() throws SQLException {
        if (!state.getClickHouseOptions().uniqExactnessOracle) {
            throw new IgnoreMeException();
        }
        List<ClickHouseTable> tables = state.getSchema().getRandomTableNonEmptyTables().getTables();
        if (tables.isEmpty()) {
            throw new IgnoreMeException();
        }
        ClickHouseTable table = Randomly.fromList(tables);
        if (table.isView()) {
            throw new IgnoreMeException();
        }
        List<ClickHouseColumn> eligible = table.getColumns().stream()
                .filter(c -> !c.isAlias() && !c.isMaterialized()).filter(ClickHouseUniqExactnessOracle::isEligible)
                .collect(Collectors.toList());
        if (eligible.isEmpty()) {
            throw new IgnoreMeException();
        }

        String tableQ = quote(table.getName());
        ClickHouseColumn c = Randomly.fromList(eligible);
        String cRef = quote(c.getName());

        String singleArm = "SELECT toString(uniqExact(" + cRef + ")) AS a, toString(count(DISTINCT " + cRef
                + ")) AS b, toString(length(groupUniqArray(" + cRef + "))) AS d FROM " + tableQ;
        runThreeColumnAgreement(singleArm, "uniqExact(c)==count(DISTINCT c)==length(groupUniqArray(c)) col="
                + c.getName());

        if (eligible.size() >= 2) {
            ClickHouseColumn c1 = eligible.get((int) Randomly.getNotCachedInteger(0, eligible.size()));
            ClickHouseColumn c2;
            do {
                c2 = eligible.get((int) Randomly.getNotCachedInteger(0, eligible.size()));
            } while (c2.getName().equals(c1.getName()));
            String r1 = quote(c1.getName());
            String r2 = quote(c2.getName());
            String pairArm = "SELECT toString(uniqExact(" + r1 + ", " + r2 + ")) AS a, toString(count(DISTINCT ("
                    + r1 + ", " + r2 + "))) AS b FROM " + tableQ;
            runTwoColumnAgreement(pairArm,
                    "uniqExact(c1,c2)==count(DISTINCT (c1,c2)) cols=" + c1.getName() + "," + c2.getName());
        }
    }

    private void runThreeColumnAgreement(String query, String label) throws SQLException {
        if (state.getOptions().logEachSelect()) {
            state.getLogger().writeCurrent(query);
        }
        try (Statement s = state.getConnection().createStatement(); ResultSet rs = s.executeQuery(query)) {
            if (!rs.next()) {
                throw new IgnoreMeException();
            }
            String a = rs.getString(1);
            String b = rs.getString(2);
            String d = rs.getString(3);
            if (!a.equals(b) || !a.equals(d)) {
                throw new AssertionError(String.format(
                        "uniq-exactness mismatch [%s]:%n  Q: %s%n  uniqExact=%s count(DISTINCT)=%s length(groupUniqArray)=%s",
                        label, query, a, b, d));
            }
        } catch (SQLException ex) {
            throw maybeIgnore(ex);
        }
    }

    private void runTwoColumnAgreement(String query, String label) throws SQLException {
        if (state.getOptions().logEachSelect()) {
            state.getLogger().writeCurrent(query);
        }
        try (Statement s = state.getConnection().createStatement(); ResultSet rs = s.executeQuery(query)) {
            if (!rs.next()) {
                throw new IgnoreMeException();
            }
            String a = rs.getString(1);
            String b = rs.getString(2);
            if (!a.equals(b)) {
                throw new AssertionError(String.format(
                        "uniq-exactness mismatch [%s]:%n  Q: %s%n  uniqExact=%s count(DISTINCT)=%s", label, query, a,
                        b));
            }
        } catch (SQLException ex) {
            throw maybeIgnore(ex);
        }
    }

    private SQLException maybeIgnore(SQLException ex) {
        if (ex.getMessage() != null && errors.errorIsExpected(ex.getMessage())) {
            throw new IgnoreMeException();
        }
        return ex;
    }

    static boolean isEligible(ClickHouseColumn c) {
        ClickHouseType term = c.getType().getTypeTerm();
        while (term instanceof ClickHouseType.LowCardinality lc) {
            term = lc.inner();
        }
        if (term instanceof ClickHouseType.Nullable) {
            return false;
        }
        ClickHouseDataType t = c.getType().getType();
        switch (t) {
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
        case String:
            return true;
        default:
            return false;
        }
    }

    static String quote(String identifier) {
        return "`" + identifier.replace("`", "``") + "`";
    }
}
