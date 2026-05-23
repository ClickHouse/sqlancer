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

/**
 * JOIN-algorithm differential oracle.
 *
 * <p>
 * ClickHouse implements every JOIN variant through one of several algorithms (selected via the {@code join_algorithm}
 * setting): {@code hash}, {@code parallel_hash}, {@code partial_merge}, {@code grace_hash}, {@code direct},
 * {@code full_sorting_merge}. The algorithms are supposed to be result-equivalent but historically diverge:
 * ClickHouse#100781 was a grace-hash wrong-result bug specifically tied to {@code grace_hash_join_initial_buckets}; the
 * SEMI/ANTI conversion path lives almost entirely inside the algorithm dispatch.
 *
 * <p>
 * The oracle reuses {@link ClickHouseTLPBase} to generate a SELECT (with --test-joins on, this will include a JOIN
 * clause about half the time). It then issues the same query under three algorithm profiles:
 *
 * <ol>
 * <li>{@code join_algorithm = 'hash'} -- the conservative baseline.</li>
 * <li>{@code join_algorithm = 'partial_merge'} -- triggers the merge-join shape.</li>
 * <li>{@code join_algorithm = 'grace_hash', grace_hash_join_initial_buckets = 4} -- bucket-spilling code path that has
 * the most recent regression history (#100781).</li>
 * </ol>
 *
 * <p>
 * Iterations that produced a no-JOIN base SELECT short-circuit with {@code IgnoreMeException}: the oracle is only
 * informative when the query under test actually contains a JOIN. The TLP base's own smoke check runs first, so any
 * generator slip (malformed ON clause, type mismatch) is absorbed before the algorithm sweep.
 */
public class ClickHouseJoinAlgorithmOracle extends ClickHouseTLPBase {

    // Server-side caps appended to every algorithm-sweep query. The OOM that previously killed
    // worker threads originated in the Java RowBinary parser materialising every cell into an
    // ArrayList<String>: a 50k x 50k self-join produces 2.5 billion rows and exhausts even a
    // 12 GiB heap before the comparator runs. Capping result rows + JOIN state + per-query
    // memory at the server makes the unsupported corner trip a tolerated error
    // (TOO_MANY_ROWS_OR_BYTES / SET_SIZE_LIMIT_EXCEEDED / MEMORY_LIMIT_EXCEEDED) instead of
    // OOM-killing the JVM. Tolerated by the patterns added in the ctor below.
    private static final String CAPS = "max_result_rows = 1000000, result_overflow_mode = 'throw', "
            + "max_bytes_in_join = 268435456, max_memory_usage = 1073741824";

    public ClickHouseJoinAlgorithmOracle(ClickHouseGlobalState state) {
        super(state);
        ClickHouseErrors.addSessionSettingsErrors(errors);
        // partial_merge and grace_hash do not implement every JOIN shape; the server raises e.g.
        // "Can't execute any of specified algorithms for specified strictness/kind and right
        // storage type. (NOT_IMPLEMENTED)" or "Join algorithm 'partial_merge' is not supported
        // with strictness ANY (BAD_ARGUMENTS)". These are not wrong-result bugs -- the algorithm
        // sweep is uninformative for the unsupported corner -- so we absorb them as expected.
        errors.add("Can't execute any of specified algorithms");
        errors.add("Join algorithm");
        errors.add("is not supported");
        errors.add("is not implemented");
        // Caps appended to every algorithm-sweep query. See CAPS constant.
        errors.add("Limit for result exceeded");
        errors.add("Limit for JOIN exceeded");
        errors.add("Memory limit");
        errors.add("memory limit exceeded");
        errors.add("MEMORY_LIMIT_EXCEEDED");
    }

    @Override
    public void check() throws SQLException {
        super.check();
        // The TLPBase smoke check picks join shape probabilistically. If this run produced no
        // JOIN, the algorithm sweep is uninformative; bail.
        if (select.getJoinClauses().isEmpty()) {
            throw new IgnoreMeException();
        }
        // ANY and SEMI JOIN shapes are explicitly non-deterministic: ANY picks "some" matched row
        // and that choice may differ between algorithms; SEMI projects same-side columns but the
        // dedup ordering across algorithms is implementation-defined when duplicate keys exist.
        // Comparing them across join_algorithm settings would produce algorithm-induced
        // false-positive diffs, not wrong-result bugs. Skip the iteration when any clause is one
        // of these shapes.
        for (ClickHouseJoin j : select.getJoinClauses()) {
            if (!isAlgorithmDeterministic(j.getType())) {
                throw new IgnoreMeException();
            }
        }
        select.setWhereClause(null);
        String baseQuery = ClickHouseVisitor.asString(select);

        // Force a stable row order via the result-ordering helper: the algorithm-specific paths
        // can shuffle rows freely, and ComparatorHelper.assumeResultSetsAreEqual normalises by
        // sorting strings, so no explicit ORDER BY is required here.
        String qHash = baseQuery + " SETTINGS join_algorithm = 'hash', " + CAPS;
        String qMerge = baseQuery + " SETTINGS join_algorithm = 'partial_merge', " + CAPS;
        String qGrace = baseQuery + " SETTINGS join_algorithm = 'grace_hash', grace_hash_join_initial_buckets = "
                + Randomly.fromOptions(1, 4, 32) + ", " + CAPS;

        List<String> rowsHash;
        try {
            rowsHash = ComparatorHelper.getResultSetFirstColumnAsString(qHash, errors, state);
        } catch (IgnoreMeException e) {
            throw e;
        }
        List<String> rowsMerge = ComparatorHelper.getResultSetFirstColumnAsString(qMerge, errors, state);
        List<String> rowsGrace = ComparatorHelper.getResultSetFirstColumnAsString(qGrace, errors, state);
        ComparatorHelper.assumeResultSetsAreEqual(rowsHash, rowsMerge, qHash, List.of(qMerge), state);
        ComparatorHelper.assumeResultSetsAreEqual(rowsHash, rowsGrace, qHash, List.of(qGrace), state);
    }

    private static boolean isAlgorithmDeterministic(ClickHouseJoin.JoinType type) {
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
