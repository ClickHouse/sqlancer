package sqlancer.clickhouse.oracle.join;

import java.sql.SQLException;
import java.util.List;

import sqlancer.ComparatorHelper;
import sqlancer.IgnoreMeException;
import sqlancer.Randomly;
import sqlancer.clickhouse.ClickHouseErrors;
import sqlancer.clickhouse.ClickHouseProvider.ClickHouseGlobalState;
import sqlancer.clickhouse.ClickHouseVisitor;
import sqlancer.clickhouse.ast.ClickHouseExpression.ClickHouseJoin;
import sqlancer.clickhouse.oracle.tlp.ClickHouseTLPBase;

public class ClickHouseJoinAlgorithmOracle extends ClickHouseTLPBase {

    static final String CAPS = "max_result_rows = 1000000, result_overflow_mode = 'throw', "
            + "max_bytes_in_join = 268435456, max_memory_usage = 1073741824";

    public ClickHouseJoinAlgorithmOracle(ClickHouseGlobalState state) {
        super(state);
        ClickHouseErrors.addSessionSettingsErrors(errors);

        errors.add("Can't execute any of specified algorithms");
        errors.add("Join algorithm");
        errors.add("is not supported");
        errors.add("is not implemented");
        addResourceCapErrors(errors);
    }

    static void addResourceCapErrors(sqlancer.common.query.ExpectedErrors errors) {
        errors.add("Limit for result exceeded");
        errors.add("Limit for JOIN exceeded");
        errors.add("Memory limit");
        errors.add("memory limit exceeded");
        errors.add("(MEMORY_LIMIT_EXCEEDED)");
    }

    @Override
    public void check() throws SQLException {
        super.check();

        if (select.getJoinClauses().isEmpty()) {
            throw new IgnoreMeException();
        }

        for (ClickHouseJoin j : select.getJoinClauses()) {
            if (!isAlgorithmDeterministic(j.getType())) {
                throw new IgnoreMeException();
            }
        }
        select.setWhereClause(null);
        String baseQuery = ClickHouseVisitor.asString(select);

        String qHash = baseQuery + " SETTINGS join_algorithm = 'hash', " + CAPS;
        String qMerge = baseQuery + " SETTINGS join_algorithm = 'partial_merge', " + CAPS;
        String qGrace = baseQuery + " SETTINGS join_algorithm = 'grace_hash', grace_hash_join_initial_buckets = "
                + Randomly.fromOptions(1, 4, 32) + ", " + CAPS;
        String qParallelSort = baseQuery + " SETTINGS join_algorithm = 'parallel_full_sorting_merge', " + CAPS;

        List<String> rowsHash;
        try {
            rowsHash = ComparatorHelper.getResultSetFirstColumnAsString(qHash, errors, state);
        } catch (IgnoreMeException e) {
            throw e;
        }
        List<String> rowsMerge = ComparatorHelper.getResultSetFirstColumnAsString(qMerge, errors, state);
        List<String> rowsGrace = ComparatorHelper.getResultSetFirstColumnAsString(qGrace, errors, state);
        List<String> rowsParallelSort = ComparatorHelper.getResultSetFirstColumnAsString(qParallelSort, errors, state);
        ComparatorHelper.assumeResultSetsAreEqual(rowsHash, rowsMerge, qHash, List.of(qMerge), state);
        ComparatorHelper.assumeResultSetsAreEqual(rowsHash, rowsGrace, qHash, List.of(qGrace), state);
        ComparatorHelper.assumeResultSetsAreEqual(rowsHash, rowsParallelSort, qHash, List.of(qParallelSort), state);
    }

    static boolean isAlgorithmDeterministic(ClickHouseJoin.JoinType type) {
        switch (type) {
        case INNER:
        case CROSS:
        case LEFT_OUTER:
        case RIGHT_OUTER:
        case FULL_OUTER:
        case LEFT_ANTI:
        case RIGHT_ANTI:
            return true;
        case LEFT_ANY:
        case RIGHT_ANY:
        case ANY_INNER:
        case LEFT_SEMI:
        case RIGHT_SEMI:
            return false;
        default:
            return false;
        }
    }
}
