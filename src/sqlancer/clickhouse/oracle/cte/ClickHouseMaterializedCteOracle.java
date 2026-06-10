package sqlancer.clickhouse.oracle.cte;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import com.clickhouse.data.ClickHouseDataType;

import sqlancer.ComparatorHelper;
import sqlancer.IgnoreMeException;
import sqlancer.Randomly;
import sqlancer.clickhouse.ClickHouseErrors;
import sqlancer.clickhouse.ClickHouseProvider.ClickHouseGlobalState;
import sqlancer.clickhouse.ClickHouseSchema;
import sqlancer.clickhouse.ClickHouseSchema.ClickHouseColumn;
import sqlancer.clickhouse.ClickHouseSchema.ClickHouseTable;
import sqlancer.common.oracle.TestOracle;
import sqlancer.common.query.ExpectedErrors;

/**
 * Materialized-CTE differential oracle (Unit 8): covers the experimental materialized CTEs ClickHouse gained in 26.3
 * via PR #94849 -- {@code WITH name AS MATERIALIZED (subquery)}, gated by {@code enable_materialized_cte}
 * (default <b>false</b>, tier EXPERIMENTAL on head).
 *
 * <p>
 * Invariant: for a deterministic CTE body, materializing the CTE once and reading the stored result must produce
 * exactly the same multiset as inlining the body at every reference site (the plain {@code WITH name AS (subquery)}
 * form). The bug class is double evaluation / wrong result vs the inlined form: a materialized snapshot that is
 * truncated, deduplicated, re-evaluated, or read with the wrong column mapping. The outer query references the CTE
 * <b>1-3 times</b> on purpose -- multiplicity &gt; 1 (self-join, scalar-subquery position, UNION ALL of two
 * references, chained CTEs) is exactly where materialize-once vs inline-twice semantics can diverge buggily even for
 * deterministic bodies; a single reference would only catch the trivial cases.
 *
 * <p>
 * Determinism rules for the body (so any divergence is a genuine bug): integer columns only (no float rendering /
 * ordering noise), aggregate restricted to {@code count()}, no nondeterministic functions, and no LIMIT anywhere in
 * the body (LIMIT without ORDER BY is nondeterministic; ORDER BY alone inside a CTE body is pointless, so bodies are
 * LIMIT-free and ORDER-BY-free). The single projected column is {@code toString(tuple(...))} over integer values, so
 * rows compare as strings and are never SQL NULL (a tuple renders even when its elements are NULL), making the Java
 * sort for the multiset compare safe.
 *
 * <p>
 * <b>Probe-gating mechanics:</b> the gate is experimental and default-false, and may not exist at all on pre-26.3
 * images (or may be renamed on a future head). The first {@code check()} per JVM probes
 * {@code SELECT 1 SETTINGS enable_materialized_cte = 1}. The probe (and every oracle query) carries the gate as a
 * per-query SETTINGS clause, NOT a standalone {@code SET}: the client-v2 transport pools connections, so a SET issued
 * on one pooled connection is not guaranteed to bind to the connection any later request runs on. If the probe fails
 * in the UNKNOWN_SETTING family, the oracle self-disables for the rest of the JVM lifetime and every subsequent
 * {@code check()} is an immediate {@code IgnoreMeException} -- the suite stays runnable on older images.
 *
 * <p>
 * Soundness note: the materialized and inlined forms run as two separate statements (the ProjectionToggle precedent
 * for fleet-table setting differentials), so a concurrent mutation landing between the two reads could in principle
 * produce a spurious mismatch. On the dev-vm this race is masked by the mounted {@code mutations_sync=2} /
 * {@code alter_sync=2} profile (see CLAUDE.md config-parity note); on other environments a mismatch should be
 * replayed before filing.
 */
public class ClickHouseMaterializedCteOracle implements TestOracle<ClickHouseGlobalState> {

    static final String GATE_SETTING = "enable_materialized_cte";
    static final String PROBE_QUERY = "SELECT 1 SETTINGS " + GATE_SETTING + " = 1";

    // Probe state, once per JVM. UNPROBED -> (ENABLED | DISABLED) on the first check() that reaches
    // the server; a transport failure during the probe leaves it UNPROBED so a later check() retries.
    // The benign race (two threads probing concurrently) is idempotent: both observe the same server.
    private static final int UNPROBED = 0;
    private static final int ENABLED = 1;
    private static final int DISABLED = 2;
    private static volatile int probeState = UNPROBED;

    /** Deterministic CTE body shapes. Integer column + integer literals only; LIMIT-free (see class javadoc). */
    enum BodyShape {
        GROUP_COUNT, // SELECT <col> AS c, count() AS n FROM <t> WHERE <col> % k = m GROUP BY <col>
        DISTINCT_FILTER, // SELECT DISTINCT <col> AS c FROM <t> WHERE <col> > m
        PLAIN_FILTER // SELECT <col> AS c FROM <t> WHERE <col> % k = m
    }

    /** Outer-query shapes; the CTE reference count is the divergence surface (see class javadoc). */
    enum OuterShape {
        SINGLE_REF, // 1 reference: the trivial baseline
        SELF_JOIN, // 2 references: x JOIN x ON c
        SCALAR_SUBQUERY, // 2 references: FROM x + (SELECT max(c) FROM x) in the projection (#101305 shape)
        UNION_ALL, // 2 references: UNION ALL of two reads, wrapped in a subquery (see renderOuter)
        CHAINED // 3 references: b is defined over a, outer joins b with a
    }

    private final ClickHouseGlobalState state;
    private final ExpectedErrors errors = new ExpectedErrors();

    public ClickHouseMaterializedCteOracle(ClickHouseGlobalState state) {
        this.state = state;
        // DELIBERATELY NARROW tolerance, pending convergence-run triage (plan Unit 8: "start with an
        // empty tolerance list"). Every statement here is hand-built SQL over one integer column and
        // integer literals, so the global expression-error list is NOT added -- under an EXPERIMENTAL
        // gate, "Missing columns" / "Cannot find column" style messages would be exactly the
        // materialization-bug shapes this oracle exists to catch. Whitelist new families only after
        // the first convergence run on head surfaces and triages them.
        ClickHouseErrors.addSessionSettingsErrors(errors); // gate drift on the non-probe path
        // Per-thread database drop/recreate race (fleet-table oracle precedent): not a CTE bug.
        errors.add("UNKNOWN_TABLE");
        errors.add("Unknown table expression identifier");
        // Environment caps: squeezed CH container (-m=...) and the provider-pinned
        // max_result_rows=1_000_000 / result_overflow_mode='throw'. Self-join / chained shapes over a
        // duplicate-heavy fleet column can legitimately trip the row cap; uninformative, not a bug.
        errors.add("(MEMORY_LIMIT_EXCEEDED)");
        errors.add("memory limit exceeded");
        errors.add("Limit for result exceeded");
        errors.add("TOO_MANY_ROWS_OR_BYTES");
        // Benign load-shed timeout under a saturated server; not a wrong-result signal for this oracle.
        errors.add("TIMEOUT_EXCEEDED");
        errors.add("Timeout exceeded");
    }

    @Override
    public void check() throws SQLException {
        probeGate();

        ClickHouseSchema schema = state.getSchema();
        List<ClickHouseTable> tables = schema.getRandomTableNonEmptyTables().getTables();
        if (tables.isEmpty()) {
            throw new IgnoreMeException();
        }
        ClickHouseTable table = tables.get((int) Randomly.getNotCachedInteger(0, tables.size()));
        if (table.isView()) {
            throw new IgnoreMeException();
        }
        List<ClickHouseColumn> intCols = table.getColumns().stream()
                .filter(c -> isExactInteger(c.getType().getType())).collect(Collectors.toList());
        if (intCols.isEmpty()) {
            throw new IgnoreMeException();
        }
        ClickHouseColumn col = Randomly.fromList(intCols);

        BodyShape body = Randomly.fromOptions(BodyShape.values());
        OuterShape outer = Randomly.fromOptions(OuterShape.values());
        int k = (int) Randomly.getNotCachedInteger(2, 8); // modulo divisor in [2, 7]
        long m = body == BodyShape.DISTINCT_FILTER ? Randomly.getNotCachedInteger(-100, 101) // threshold
                : Randomly.getNotCachedInteger(0, k); // remainder in [0, k)

        String materializedStmt = renderStatement(outer, body, table.getName(), col.getName(), k, m, true);
        String inlinedStmt = renderStatement(outer, body, table.getName(), col.getName(), k, m, false);

        // Either read hitting a tolerated error throws IgnoreMeException inside the helper, so a
        // partial pair is never compared.
        List<String> materializedRows = ComparatorHelper.getResultSetFirstColumnAsString(materializedStmt, errors,
                state);
        List<String> inlinedRows = ComparatorHelper.getResultSetFirstColumnAsString(inlinedStmt, errors, state);

        // toString(tuple(...)) is never SQL NULL, so rows contain no Java nulls; map defensively
        // anyway so a transport surprise cannot NPE inside ComparableTimSort (a known noise family).
        List<String> matSorted = sortedNonNull(materializedRows);
        List<String> inlSorted = sortedNonNull(inlinedRows);
        if (!matSorted.equals(inlSorted)) {
            List<String> diff = boundedDiff(matSorted, inlSorted, 20);
            throw new AssertionError(String.format(
                    "materialized-CTE divergence: %d rows (materialized) vs %d rows (inlined).%n"
                            + "  materialized: %s%n  inlined: %s%n  first differing values (max 20): %s",
                    matSorted.size(), inlSorted.size(), materializedStmt, inlinedStmt, diff));
        }
    }

    private void probeGate() throws SQLException {
        int s = probeState;
        if (s == ENABLED) {
            return;
        }
        if (s == DISABLED) {
            throw new IgnoreMeException();
        }
        // Probe under its OWN tolerance: only the session-settings family (UNKNOWN_SETTING and
        // friends). Anything else thrown by `SELECT 1` is a real problem and must surface.
        ExpectedErrors probeErrors = new ExpectedErrors();
        ClickHouseErrors.addSessionSettingsErrors(probeErrors);
        try {
            ComparatorHelper.getResultSetFirstColumnAsString(PROBE_QUERY, probeErrors, state);
            probeState = ENABLED;
        } catch (IgnoreMeException e) {
            // UNKNOWN_SETTING family: the gate does not exist on this server. Self-disable forever.
            probeState = DISABLED;
            throw e;
        }
    }

    // --- rendering (package-private + static for DB-free tests) ---

    static String renderBody(BodyShape shape, String tableName, String columnName, long k, long m) {
        String col = "`" + columnName + "`";
        switch (shape) {
        case GROUP_COUNT:
            return "SELECT " + col + " AS c, count() AS n FROM " + tableName + " WHERE " + col + " % " + k + " = " + m
                    + " GROUP BY " + col;
        case DISTINCT_FILTER:
            return "SELECT DISTINCT " + col + " AS c FROM " + tableName + " WHERE " + col + " > " + m;
        case PLAIN_FILTER:
            return "SELECT " + col + " AS c FROM " + tableName + " WHERE " + col + " % " + k + " = " + m;
        default:
            throw new AssertionError(shape);
        }
    }

    static String renderOuter(OuterShape shape, BodyShape body) {
        // GROUP_COUNT bodies also project the per-group count n -- carrying it through the outer
        // tuple means a materialization bug that corrupts the aggregate (not just the key) is caught.
        boolean hasN = body == BodyShape.GROUP_COUNT;
        switch (shape) {
        case SINGLE_REF:
            return "SELECT toString(tuple(" + (hasN ? "c, n" : "c") + ")) FROM mcte_x";
        case SELF_JOIN:
            return "SELECT toString(tuple(" + (hasN ? "x1.c, x1.n, x2.c, x2.n" : "x1.c, x2.c")
                    + ")) FROM mcte_x AS x1 JOIN mcte_x AS x2 ON x1.c = x2.c";
        case SCALAR_SUBQUERY:
            // The #101305 fixed-crash shape: the CTE read from FROM and from a scalar-subquery
            // position in the same query. max(c) over an empty CTE still yields exactly one row
            // (aggregate without GROUP BY), so the scalar subquery never errors on emptiness.
            return "SELECT toString(tuple(" + (hasN ? "c, n, " : "c, ")
                    + "(SELECT max(c) FROM mcte_x))) FROM mcte_x";
        case UNION_ALL: {
            // Wrapped in a subquery so the trailing SETTINGS clause of the materialized form
            // attaches to the single top-level SELECT (a trailing SETTINGS after a bare UNION ALL
            // would bind to the second branch only).
            String branch = "SELECT toString(tuple(" + (hasN ? "c, n" : "c") + ")) AS r FROM mcte_x";
            return "SELECT r FROM (" + branch + " UNION ALL " + branch + ")";
        }
        case CHAINED:
            // mcte_b is defined over mcte_a (see renderStatement); the outer joins both, so mcte_a
            // is referenced twice in total (once in b's body, once here) plus the mcte_b read.
            return "SELECT toString(tuple(" + (hasN ? "b1.c, a1.n" : "b1.c, a1.c")
                    + ")) FROM mcte_b AS b1 JOIN mcte_a AS a1 ON b1.c = a1.c";
        default:
            throw new AssertionError(shape);
        }
    }

    /**
     * Renders the full statement. {@code materialized = true} produces form A: {@code AS MATERIALIZED (...)} bodies
     * plus the trailing {@code SETTINGS enable_materialized_cte = 1}; {@code materialized = false} produces form B,
     * the plain inlined {@code AS (...)} form with no settings clause. The two forms are otherwise textually
     * identical.
     */
    static String renderStatement(OuterShape shape, BodyShape body, String tableName, String columnName, long k,
            long m, boolean materialized) {
        String as = materialized ? " AS MATERIALIZED (" : " AS (";
        String bodySql = renderBody(body, tableName, columnName, k, m);
        StringBuilder sb = new StringBuilder("WITH ");
        if (shape == OuterShape.CHAINED) {
            sb.append("mcte_a").append(as).append(bodySql).append("), ");
            // c % 2 = 0 is deterministic for negative c too (-3 % 2 = -1, filtered) and NULL-safe
            // (NULL % 2 = NULL, filtered).
            sb.append("mcte_b").append(as).append("SELECT c FROM mcte_a WHERE c % 2 = 0").append(")");
        } else {
            sb.append("mcte_x").append(as).append(bodySql).append(")");
        }
        sb.append(' ').append(renderOuter(shape, body));
        if (materialized) {
            sb.append(" SETTINGS ").append(GATE_SETTING).append(" = 1");
        }
        return sb.toString();
    }

    // --- comparison helpers ---

    private static List<String> sortedNonNull(List<String> rows) {
        List<String> sorted = new ArrayList<>(rows.size());
        for (String r : rows) {
            sorted.add(r == null ? "\\N" : r);
        }
        Collections.sort(sorted);
        return sorted;
    }

    /** Multiset difference of two sorted lists, bounded to {@code limit} entries, for the AssertionError message. */
    static List<String> boundedDiff(List<String> sortedMaterialized, List<String> sortedInlined, int limit) {
        List<String> diffs = new ArrayList<>();
        int i = 0;
        int j = 0;
        while ((i < sortedMaterialized.size() || j < sortedInlined.size()) && diffs.size() < limit) {
            if (i >= sortedMaterialized.size()) {
                diffs.add("inlined-only: " + sortedInlined.get(j++));
            } else if (j >= sortedInlined.size()) {
                diffs.add("materialized-only: " + sortedMaterialized.get(i++));
            } else {
                int cmp = sortedMaterialized.get(i).compareTo(sortedInlined.get(j));
                if (cmp == 0) {
                    i++;
                    j++;
                } else if (cmp < 0) {
                    diffs.add("materialized-only: " + sortedMaterialized.get(i++));
                } else {
                    diffs.add("inlined-only: " + sortedInlined.get(j++));
                }
            }
        }
        return diffs;
    }

    // Integer types only: exact rendering, exact grouping, exact join keys. Float is excluded
    // entirely (NaN / +-0.0 grouping and rendering noise). getType() already unwraps
    // Nullable / LowCardinality, so Nullable(Int*) columns qualify; NULLs flow through the
    // tuple rendering deterministically.
    private static boolean isExactInteger(ClickHouseDataType t) {
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
            return true;
        default:
            return false;
        }
    }

}
