package sqlancer.clickhouse.oracle.vecindex;

import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import sqlancer.ComparatorHelper;
import sqlancer.IgnoreMeException;
import sqlancer.Randomly;
import sqlancer.clickhouse.ClickHouseErrors;
import sqlancer.clickhouse.ClickHouseProvider.ClickHouseGlobalState;
import sqlancer.common.oracle.TestOracle;
import sqlancer.common.query.ExpectedErrors;
import sqlancer.common.query.SQLQueryAdapter;

public class ClickHouseVectorIndexRecallOracle implements TestOracle<ClickHouseGlobalState> {

    private static final AtomicLong CTR = new AtomicLong();

    private static final int DIM = 4;

    private static final double CONTAINMENT_TOLERANCE = 1e-4;

    private static final String HIGH_RECALL_SETTINGS = " SETTINGS use_skip_indexes = 1, "
            + "hnsw_candidate_list_size_for_search = 4096, max_limit_for_ann_queries = 1000000";

    private static final String EXACT_SCAN_SETTINGS = " SETTINGS use_skip_indexes = 0";

    private final ClickHouseGlobalState state;

    private final ExpectedErrors errors = new ExpectedErrors();

    public ClickHouseVectorIndexRecallOracle(ClickHouseGlobalState state) {
        this.state = state;

        ClickHouseErrors.addSessionSettingsErrors(errors);
        ClickHouseErrors.addExpectedExpressionErrors(errors);

        errors.add("UNKNOWN_STORAGE");
        errors.add("Unknown table engine");
        errors.add("SUPPORT_IS_DISABLED");
        errors.add("NOT_IMPLEMENTED");
        errors.add("ILLEGAL_TYPE_OF_ARGUMENT");
        errors.add("UNKNOWN_FUNCTION");
        errors.add("Unknown function");
        errors.add("Unknown setting");
        errors.add("BAD_ARGUMENTS");
        errors.add("INCORRECT_QUERY");
        errors.add("ILLEGAL_INDEX");
        errors.add("Unknown Index type");
        errors.add("Unknown index type");
        errors.add("vector similarity");
        errors.add("vector_similarity");
        errors.add("experimental");
        errors.add("allow_experimental");
        errors.add("SYNTAX_ERROR");
        errors.add("Syntax error");

        errors.add("UNKNOWN_TABLE");
        errors.add("Unknown table expression identifier");

        errors.add("(MEMORY_LIMIT_EXCEEDED)");
        errors.add("memory limit exceeded");

        errors.add("TIMEOUT_EXCEEDED");
        errors.add("Timeout exceeded");
    }

    @Override
    public void check() throws SQLException {
        if (!state.getClickHouseOptions().vectorIndexRecallOracle) {
            throw new IgnoreMeException();
        }

        long id = CTR.incrementAndGet();
        String table = state.getDatabaseName() + ".vec_" + id;
        Randomly r = state.getRandomly();

        enableExperimental();

        int rows = 40 + r.getInteger(0, 41);

        try {
            String create = "CREATE TABLE " + table + " (id UInt32, vec Array(Float32), "
                    + "INDEX idx vec TYPE vector_similarity('hnsw', 'L2Distance', " + DIM + ") GRANULARITY 1) "
                    + "ENGINE = MergeTree ORDER BY id";
            logStmt(create);
            if (!new SQLQueryAdapter(create, errors, true).execute(state)) {
                throw new IgnoreMeException();
            }

            float[][] vectors = buildVectors(rows);
            float[] query = buildQuery();
            insertVectors(table, vectors);

            long top1 = uniqueNearest(vectors, query);

            String indexTop1 = readSingleValue("SELECT toString(id) FROM " + table + " ORDER BY "
                    + l2Expr(query) + " ASC, id ASC LIMIT 1" + HIGH_RECALL_SETTINGS);
            String scanTop1 = readSingleValue("SELECT toString(id) FROM " + table + " ORDER BY "
                    + l2Expr(query) + " ASC, id ASC LIMIT 1" + EXACT_SCAN_SETTINGS);

            if (!scanTop1.equals(String.valueOf(top1))) {
                throw new IgnoreMeException();
            }
            if (!indexTop1.equals(scanTop1)) {
                throw new AssertionError(String.format(
                        "vector-index top-1 recall mismatch: query %s: HNSW index returned id %s but exact "
                                + "brute-force returned id %s (unique true nearest id %d). DDL: %s",
                        renderArrayLiteral(query), indexTop1, scanTop1, top1, create));
            }

            int k = 3;
            if (rows >= k) {
                double exactKthDistance = readDoubleValue("SELECT toString(" + l2Expr(query) + ") AS d FROM " + table
                        + " ORDER BY d ASC, id ASC LIMIT 1 OFFSET " + (k - 1) + EXACT_SCAN_SETTINGS);
                double indexMaxDistance = readDoubleValue("SELECT toString(max(d)) FROM (SELECT " + l2Expr(query)
                        + " AS d FROM " + table + " ORDER BY d ASC, id ASC LIMIT " + k + HIGH_RECALL_SETTINGS + ")");

                if (indexMaxDistance > exactKthDistance + CONTAINMENT_TOLERANCE) {
                    throw new AssertionError(String.format(
                            "vector-index k=%d containment violated: query %s: HNSW top-%d max L2Distance %.9f exceeds "
                                    + "exact %dth-nearest distance %.9f (tolerance %.0e). DDL: %s",
                            k, renderArrayLiteral(query), k, indexMaxDistance, k, exactKthDistance,
                            CONTAINMENT_TOLERANCE, create));
                }
            }
        } finally {
            dropQuietly(table);
        }
    }

    private void enableExperimental() {
        try {
            new SQLQueryAdapter("SET allow_experimental_vector_similarity_index = 1", errors, true).execute(state);
        } catch (Exception | AssertionError ignored) {

        }
    }

    private float[][] buildVectors(int rows) {
        float[][] vectors = new float[rows][DIM];
        for (int i = 0; i < rows; i++) {
            for (int d = 0; d < DIM; d++) {
                vectors[i][d] = i * (DIM + 1) + d * 7 + 13;
            }
        }
        return vectors;
    }

    private float[] buildQuery() {
        float[] q = new float[DIM];
        for (int d = 0; d < DIM; d++) {
            q[d] = d * 7 + 13 + 1;
        }
        return q;
    }

    private long uniqueNearest(float[][] vectors, float[] query) {
        long best = -1;
        double bestDistance = Double.POSITIVE_INFINITY;
        double secondDistance = Double.POSITIVE_INFINITY;
        for (int i = 0; i < vectors.length; i++) {
            double dist = squaredDistance(vectors[i], query);
            if (dist < bestDistance) {
                secondDistance = bestDistance;
                bestDistance = dist;
                best = i;
            } else if (dist < secondDistance) {
                secondDistance = dist;
            }
        }
        if (bestDistance == secondDistance) {
            throw new IgnoreMeException();
        }
        return best;
    }

    private static double squaredDistance(float[] a, float[] b) {
        double sum = 0;
        for (int d = 0; d < a.length; d++) {
            double diff = a[d] - b[d];
            sum += diff * diff;
        }
        return sum;
    }

    private void insertVectors(String table, float[][] vectors) throws SQLException {
        StringBuilder sb = new StringBuilder("INSERT INTO ").append(table).append(" (id, vec) VALUES ");
        for (int i = 0; i < vectors.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append('(').append(i).append(", ").append(renderArrayLiteral(vectors[i])).append(')');
        }
        logStmt(sb.toString());
        if (!new SQLQueryAdapter(sb.toString(), errors, true).execute(state)) {
            throw new IgnoreMeException();
        }
    }

    private static String l2Expr(float[] query) {
        return "L2Distance(vec, " + renderArrayLiteral(query) + ")";
    }

    private static String renderArrayLiteral(float[] v) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < v.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append("toFloat32(").append(formatCoordinate(v[i])).append(')');
        }
        return sb.append(']').toString();
    }

    private static String formatCoordinate(float value) {
        if (value == Math.rint(value) && !Float.isInfinite(value)) {
            return Long.toString((long) value);
        }
        return Float.toString(value);
    }

    private String readSingleValue(String query) throws SQLException {
        List<String> result = ComparatorHelper.getResultSetFirstColumnAsString(query, errors, state);
        if (result.size() != 1) {
            throw new IgnoreMeException();
        }
        return result.get(0);
    }

    private double readDoubleValue(String query) throws SQLException {
        String raw = readSingleValue(query);
        try {
            return Double.parseDouble(raw);
        } catch (NumberFormatException e) {
            throw new IgnoreMeException();
        }
    }

    private void dropQuietly(String table) {
        try {
            new SQLQueryAdapter("DROP TABLE IF EXISTS " + table, errors, true).execute(state);
        } catch (Exception | AssertionError ignored) {

        }
    }

    private void logStmt(String stmt) {
        if (state.getOptions().logEachSelect()) {
            state.getLogger().writeCurrent(stmt);
            state.getState().logStatement(stmt);
        }
    }
}
