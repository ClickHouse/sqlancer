package sqlancer.dbms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.Test;

import sqlancer.Main;

public class TestClickHouse {

    @Test
    public void testClickHouseTLPWhereGroupBy() {
        assumeTrue(TestConfig.isEnvironmentTrue(TestConfig.CLICKHOUSE_ENV));
        assertEquals(0,
                Main.executeMain("--timeout-seconds", "60", "--num-queries", TestConfig.NUM_QUERIES, "--num-threads",
                        "5", "--username", "default", "--password", "", "--database-prefix", "T1_", "clickhouse",
                        "--oracle", "TLPWhere", "--oracle", "TLPGroupBy"));
    }

    @Test
    public void testClickHouseTLPWhere() {
        assumeTrue(TestConfig.isEnvironmentTrue(TestConfig.CLICKHOUSE_ENV));
        assertEquals(0,
                Main.executeMain("--timeout-seconds", "60", "--num-queries", TestConfig.NUM_QUERIES, "--num-threads",
                        "5", "--username", "default", "--password", "", "--database-prefix", "T2_", "clickhouse",
                        "--oracle", "TLPWhere"));
    }

    @Test
    public void testClickHouseTLPHaving() {
        assumeTrue(TestConfig.isEnvironmentTrue(TestConfig.CLICKHOUSE_ENV));
        assertEquals(0,
                Main.executeMain("--log-each-select", "true", "--print-failed", "false", "--timeout-seconds", "60",
                        "--num-queries", TestConfig.NUM_QUERIES, "--num-threads", "1", "--username", "default",
                        "--password", "", "--database-prefix", "T3_", "clickhouse", "--oracle", "TLPHaving"));
    }

    @Test
    public void testClickHouseTLPGroupBy() {
        assumeTrue(TestConfig.isEnvironmentTrue(TestConfig.CLICKHOUSE_ENV));
        assertEquals(0,
                Main.executeMain("--log-each-select", "true", "--print-failed", "false", "--timeout-seconds", "60",
                        "--num-queries", TestConfig.NUM_QUERIES, "--num-threads", "5", "--username", "default",
                        "--password", "", "--database-prefix", "T4_", "clickhouse", "--oracle", "TLPGroupBy"));
    }

    @Test
    public void testClickHouseTLPDistinct() {
        assumeTrue(TestConfig.isEnvironmentTrue(TestConfig.CLICKHOUSE_ENV));
        assertEquals(0,
                Main.executeMain("--log-each-select", "true", "--print-failed", "false", "--timeout-seconds", "60",
                        "--num-queries", TestConfig.NUM_QUERIES, "--num-threads", "5", "--username", "default",
                        "--password", "", "--database-prefix", "T5_", "clickhouse", "--oracle", "TLPDistinct"));
    }

    @Test
    public void testClickHouseTLPAggregate() {
        assumeTrue(TestConfig.isEnvironmentTrue(TestConfig.CLICKHOUSE_ENV));
        assertEquals(0,
                Main.executeMain("--log-each-select", "true", "--print-failed", "false", "--timeout-seconds", "60",
                        "--num-queries", TestConfig.NUM_QUERIES, "--num-threads", "5", "--username", "default",
                        "--password", "", "--database-prefix", "T6_", "clickhouse", "--oracle", "TLPAggregate"));
    }

    @Test
    public void testClickHouseNoREC() {
        assumeTrue(TestConfig.isEnvironmentTrue(TestConfig.CLICKHOUSE_ENV));
        assertEquals(0,
                Main.executeMain("--log-each-select", "true", "--print-failed", "false", "--timeout-seconds", "60",
                        "--num-queries", TestConfig.NUM_QUERIES, "--num-threads", "1", "--username", "default",
                        "--password", "", "--database-prefix", "T7_", "clickhouse", "--oracle", "NoREC"));
    }

    @Test
    public void testClickHouseTLPWhereGroupByWithJoins() {
        assumeTrue(TestConfig.isEnvironmentTrue(TestConfig.CLICKHOUSE_ENV));
        assertEquals(0,
                Main.executeMain("--timeout-seconds", "60", "--num-queries", TestConfig.NUM_QUERIES, "--num-threads",
                        "5", "--username", "default", "--password", "", "--database-prefix", "T8_", "clickhouse",
                        "--oracle", "TLPWhere", "--oracle", "TLPGroupBy"));
    }

    @Test
    public void testClickHouseTLPWhereWithJoins() {
        assumeTrue(TestConfig.isEnvironmentTrue(TestConfig.CLICKHOUSE_ENV));
        assertEquals(0,
                Main.executeMain("--timeout-seconds", "60", "--num-queries", TestConfig.NUM_QUERIES, "--num-threads",
                        "5", "--username", "default", "--password", "", "--database-prefix", "T9_", "clickhouse",
                        "--oracle", "TLPWhere"));
    }

    @Test
    public void testClickHouseTLPHavingWithJoins() {
        assumeTrue(TestConfig.isEnvironmentTrue(TestConfig.CLICKHOUSE_ENV));
        assertEquals(0,
                Main.executeMain("--log-each-select", "true", "--print-failed", "false", "--timeout-seconds", "60",
                        "--num-queries", TestConfig.NUM_QUERIES, "--num-threads", "1", "--username", "default",
                        "--password", "", "--database-prefix", "T10_", "clickhouse", "--oracle", "TLPHaving"));
    }

    @Test
    public void testClickHouseTLPGroupByWithJoins() {
        assumeTrue(TestConfig.isEnvironmentTrue(TestConfig.CLICKHOUSE_ENV));
        assertEquals(0,
                Main.executeMain("--log-each-select", "true", "--print-failed", "false", "--timeout-seconds", "60",
                        "--num-queries", TestConfig.NUM_QUERIES, "--num-threads", "5", "--username", "default",
                        "--password", "", "--database-prefix", "T11_", "clickhouse", "--oracle", "TLPGroupBy"));
    }

    @Test
    public void testClickHouseTLPDistinctWithJoins() {
        assumeTrue(TestConfig.isEnvironmentTrue(TestConfig.CLICKHOUSE_ENV));
        assertEquals(0,
                Main.executeMain("--log-each-select", "true", "--print-failed", "false", "--timeout-seconds", "60",
                        "--num-queries", TestConfig.NUM_QUERIES, "--num-threads", "5", "--username", "default",
                        "--password", "", "--database-prefix", "T12_", "clickhouse", "--oracle", "TLPDistinct"));
    }

    @Test
    public void testClickHouseTLPAggregateWithJoins() {
        assumeTrue(TestConfig.isEnvironmentTrue(TestConfig.CLICKHOUSE_ENV));
        assertEquals(0,
                Main.executeMain("--log-each-select", "true", "--print-failed", "false", "--timeout-seconds", "60",
                        "--num-queries", TestConfig.NUM_QUERIES, "--num-threads", "5", "--username", "default",
                        "--password", "", "--database-prefix", "T13_", "clickhouse", "--oracle", "TLPAggregate"));
    }

    @Test
    public void testClickHouseNoRECWithJoins() {
        assumeTrue(TestConfig.isEnvironmentTrue(TestConfig.CLICKHOUSE_ENV));
        assertEquals(0,
                Main.executeMain("--log-each-select", "true", "--print-failed", "false", "--timeout-seconds", "60",
                        "--num-queries", TestConfig.NUM_QUERIES, "--num-threads", "1", "--username", "default",
                        "--password", "", "--database-prefix", "T14_", "clickhouse", "--oracle", "NoREC"));
    }

    @Test
    public void testClickHouseSEMR() {
        assumeTrue(TestConfig.isEnvironmentTrue(TestConfig.CLICKHOUSE_ENV));
        assertEquals(0,
                Main.executeMain("--log-each-select", "true", "--print-failed", "false", "--timeout-seconds", "60",
                        "--num-queries", TestConfig.NUM_QUERIES, "--num-threads", "1", "--username", "default",
                        "--password", "", "--database-prefix", "T15_", "clickhouse", "--oracle", "SEMR"));
    }

    @Test
    public void testClickHouseTLPDistinctWithRandomSessionSettings() {
        assumeTrue(TestConfig.isEnvironmentTrue(TestConfig.CLICKHOUSE_ENV));
        assertEquals(0,
                Main.executeMain("--log-each-select", "true", "--print-failed", "false", "--timeout-seconds", "60",
                        "--num-queries", TestConfig.NUM_QUERIES, "--num-threads", "5", "--username", "default",
                        "--password", "", "--database-prefix", "T16_", "clickhouse", "--oracle", "TLPDistinct",
                        "--random-session-settings", "true", "--random-session-settings-budget", "3"));
    }

    @Test
    public void testClickHouseMutuallyExclusive() {
        assumeTrue(TestConfig.isEnvironmentTrue(TestConfig.CLICKHOUSE_ENV));
        // Inversion: this test asserts the run rejects the incompatible flag combination before
        // any thread spawns. A return of 0 would mean the mutual-exclusion pre-flight gate is gone.
        assertNotEquals(0,
                Main.executeMain("--timeout-seconds", "60", "--num-queries", TestConfig.NUM_QUERIES, "--num-threads",
                        "1", "--username", "default", "--password", "", "--database-prefix", "T17_", "clickhouse",
                        "--oracle", "SEMR", "--random-session-settings", "true"));
    }

    @Test
    public void testClickHouseEET() {
        assumeTrue(TestConfig.isEnvironmentTrue(TestConfig.CLICKHOUSE_ENV));
        assertEquals(0,
                Main.executeMain("--log-each-select", "true", "--print-failed", "false", "--timeout-seconds", "60",
                        "--num-queries", TestConfig.NUM_QUERIES, "--num-threads", "1", "--username", "default",
                        "--password", "", "--database-prefix", "T18_", "clickhouse", "--oracle", "EET"));
    }

}
