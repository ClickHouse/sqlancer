package sqlancer.clickhouse.oracle.join;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sqlancer.clickhouse.oracle.join.ClickHouseJoinReorderOracle.JoinKind.FULL;
import static sqlancer.clickhouse.oracle.join.ClickHouseJoinReorderOracle.JoinKind.INNER;
import static sqlancer.clickhouse.oracle.join.ClickHouseJoinReorderOracle.JoinKind.LEFT;
import static sqlancer.clickhouse.oracle.join.ClickHouseJoinReorderOracle.JoinKind.LEFT_ANTI;
import static sqlancer.clickhouse.oracle.join.ClickHouseJoinReorderOracle.JoinKind.LEFT_SEMI;
import static sqlancer.clickhouse.oracle.join.ClickHouseJoinReorderOracle.JoinKind.RIGHT_ANTI;
import static sqlancer.clickhouse.oracle.join.ClickHouseJoinReorderOracle.JoinKind.RIGHT_SEMI;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * DB-free unit tests for {@link ClickHouseJoinReorderOracle}'s static query construction and comparison helpers: the
 * deterministic-projection rule for SEMI/ANTI chains, exact join-chain SQL rendering per arm, and the bounded multiset
 * diff that backs the pairwise arm comparison.
 */
class ClickHouseJoinReorderOracleTest {

    private static final List<String> TABLES2 = List.of("db.jreord_1_t0", "db.jreord_1_t1");
    private static final List<String> TABLES3 = List.of("db.jreord_1_t0", "db.jreord_1_t1", "db.jreord_1_t2");

    // ---- (b) deterministic-projection rule ----

    @Test
    void leftSemiChainProjectsLeftmostTableOnly() {
        assertEquals(List.of(0), ClickHouseJoinReorderOracle.deterministicTables(List.of(LEFT_SEMI)));
        assertEquals(List.of(0), ClickHouseJoinReorderOracle.deterministicTables(List.of(LEFT_ANTI)));
        // Two left-restricted joins agree on the driving table.
        assertEquals(List.of(0), ClickHouseJoinReorderOracle.deterministicTables(List.of(LEFT_SEMI, LEFT_ANTI)));
        // A non-restricting join does not widen the set back.
        assertEquals(List.of(0), ClickHouseJoinReorderOracle.deterministicTables(List.of(LEFT_SEMI, INNER)));
    }

    @Test
    void rightSemiAntiChainProjectsRightTableOnly() {
        assertEquals(List.of(1), ClickHouseJoinReorderOracle.deterministicTables(List.of(RIGHT_ANTI)));
        assertEquals(List.of(1), ClickHouseJoinReorderOracle.deterministicTables(List.of(RIGHT_SEMI)));
        // The right table is the one attached by THAT join: a RIGHT SEMI as the second join of a
        // three-table chain keeps a2.
        assertEquals(List.of(2), ClickHouseJoinReorderOracle.deterministicTables(List.of(INNER, RIGHT_SEMI)));
    }

    @Test
    void mixedConflictingChainHasNoDeterministicTable() {
        // LEFT SEMI pins {a0}, RIGHT ANTI pins {a2}: empty intersection -> count()-only projection.
        assertEquals(List.of(), ClickHouseJoinReorderOracle.deterministicTables(List.of(LEFT_SEMI, RIGHT_ANTI)));
        // Two RIGHT-restricted joins also conflict (each pins its own right table).
        assertEquals(List.of(), ClickHouseJoinReorderOracle.deterministicTables(List.of(RIGHT_SEMI, RIGHT_ANTI)));
    }

    @Test
    void unrestrictedChainProjectsAllTables() {
        assertEquals(List.of(0, 1, 2), ClickHouseJoinReorderOracle.deterministicTables(List.of(INNER, LEFT)));
        assertEquals(List.of(0, 1), ClickHouseJoinReorderOracle.deterministicTables(List.of(FULL)));
        assertEquals(List.of(0, 1, 2, 3), ClickHouseJoinReorderOracle.deterministicTables(List.of(FULL, LEFT, INNER)));
    }

    // ---- (a) join-chain rendering ----

    @Test
    void rendersUnrestrictedChainWithAllColumnsAndDefaultArm() {
        String sql = ClickHouseJoinReorderOracle.renderQuery(List.of(INNER, LEFT), TABLES3, List.of(0, 1), null,
                ClickHouseJoinReorderOracle.ARM_REORDER_ON);
        assertEquals("SELECT toString(tuple(a0.k, a0.v, a0.s, a1.k, a1.v, a1.s, a2.k, a2.v, a2.s)) "
                + "FROM db.jreord_1_t0 AS a0 INNER JOIN db.jreord_1_t1 AS a1 ON a0.k = a1.k "
                + "LEFT JOIN db.jreord_1_t2 AS a2 ON a1.k = a2.k "
                + "SETTINGS query_plan_optimize_join_order_limit = 10", sql);
    }

    @Test
    void rendersLeftSemiWithLeftOnlyProjectionAndOffArm() {
        String sql = ClickHouseJoinReorderOracle.renderQuery(List.of(LEFT_SEMI), TABLES2, List.of(0), null,
                ClickHouseJoinReorderOracle.ARM_REORDER_OFF);
        assertEquals("SELECT toString(tuple(a0.k, a0.v, a0.s)) FROM db.jreord_1_t0 AS a0 "
                + "LEFT SEMI JOIN db.jreord_1_t1 AS a1 ON a0.k = a1.k "
                + "SETTINGS query_plan_optimize_join_order_limit = 0", sql);
    }

    @Test
    void rendersRightAntiWithRightOnlyProjection() {
        String sql = ClickHouseJoinReorderOracle.renderQuery(List.of(RIGHT_ANTI), TABLES2, List.of(0), null,
                ClickHouseJoinReorderOracle.ARM_REORDER_OFF);
        assertEquals("SELECT toString(tuple(a1.k, a1.v, a1.s)) FROM db.jreord_1_t0 AS a0 "
                + "RIGHT ANTI JOIN db.jreord_1_t1 AS a1 ON a0.k = a1.k "
                + "SETTINGS query_plan_optimize_join_order_limit = 0", sql);
    }

    @Test
    void rendersConflictingChainAsCountOnly() {
        String sql = ClickHouseJoinReorderOracle.renderQuery(List.of(LEFT_SEMI, RIGHT_ANTI), TABLES3, List.of(0, 0),
                null, ClickHouseJoinReorderOracle.ARM_REORDER_ON);
        assertEquals("SELECT toString(count()) FROM db.jreord_1_t0 AS a0 "
                + "LEFT SEMI JOIN db.jreord_1_t1 AS a1 ON a0.k = a1.k "
                + "RIGHT ANTI JOIN db.jreord_1_t2 AS a2 ON a0.k = a2.k "
                + "SETTINGS query_plan_optimize_join_order_limit = 10", sql);
    }

    @Test
    void rendersCrossRelationWhereAndRandomizeArm() {
        String sql = ClickHouseJoinReorderOracle.renderQuery(List.of(FULL, INNER), TABLES3, List.of(0, 1),
                "a0.v < a2.v", ClickHouseJoinReorderOracle.ARM_REORDER_RANDOMIZE);
        assertEquals("SELECT toString(tuple(a0.k, a0.v, a0.s, a1.k, a1.v, a1.s, a2.k, a2.v, a2.s)) "
                + "FROM db.jreord_1_t0 AS a0 FULL JOIN db.jreord_1_t1 AS a1 ON a0.k = a1.k "
                + "INNER JOIN db.jreord_1_t2 AS a2 ON a1.k = a2.k WHERE a0.v < a2.v "
                + "SETTINGS query_plan_optimize_join_order_limit = 10, query_plan_optimize_join_order_randomize = 1",
                sql);
    }

    @Test
    void onClauseCanReferenceAnyEarlierAlias() {
        // Join 2's ON references a0, not a1 (the random earlier-alias pick).
        String sql = ClickHouseJoinReorderOracle.renderQuery(List.of(LEFT, LEFT), TABLES3, List.of(0, 0), null,
                ClickHouseJoinReorderOracle.ARM_REORDER_ON);
        assertTrue(sql.contains("LEFT JOIN db.jreord_1_t2 AS a2 ON a0.k = a2.k"), sql);
    }

    // ---- (c) multiset comparison helper ----

    @Test
    void equalMultisetsProduceEmptyDiff() {
        assertEquals(List.of(), ClickHouseJoinReorderOracle.multisetDiff(List.of(), List.of(), 20));
        // Order does not matter; duplicate counts do.
        List<String> first = List.of("(1,2,'a')", "(3,4,'b')", "(1,2,'a')");
        List<String> second = List.of("(1,2,'a')", "(1,2,'a')", "(3,4,'b')");
        assertEquals(List.of(), ClickHouseJoinReorderOracle.multisetDiff(first, second, 20));
    }

    @Test
    void duplicateCountMismatchIsDetected() {
        // Set-equal but multiset-unequal: the duplicated tuple shows up once with its excess count.
        List<String> first = List.of("(1,2,'a')", "(1,2,'a')");
        List<String> second = List.of("(1,2,'a')");
        assertEquals(List.of("(1,2,'a') (+1 first)"), ClickHouseJoinReorderOracle.multisetDiff(first, second, 20));
    }

    @Test
    void valueOnlyInSecondIsAttributedToSecond() {
        List<String> diff = ClickHouseJoinReorderOracle.multisetDiff(List.of("(1,1,'x')"),
                List.of("(1,1,'x')", "(2,2,'y')", "(2,2,'y')"), 20);
        assertEquals(List.of("(2,2,'y') (+2 second)"), diff);
    }

    @Test
    void diffIsBoundedByLimit() {
        List<String> first = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            first.add(String.format("(%02d)", i));
        }
        List<String> diff = ClickHouseJoinReorderOracle.multisetDiff(first, List.of(), 20);
        assertEquals(20, diff.size());
        assertEquals("(00) (+1 first)", diff.get(0));
    }

    @Test
    void nullEntriesAreDefensivelyRendered() {
        // Tuple-rendered strings are never SQL NULL, but the helper must not NPE if the transport
        // ever hands one back.
        List<String> withNull = new ArrayList<>(Arrays.asList((String) null));
        List<String> diff = ClickHouseJoinReorderOracle.multisetDiff(withNull, Collections.emptyList(), 20);
        assertEquals(List.of("\\N (+1 first)"), diff);
    }
}
