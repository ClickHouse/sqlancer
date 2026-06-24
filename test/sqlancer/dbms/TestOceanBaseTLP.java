package sqlancer.dbms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.Test;

import sqlancer.Main;

public class TestOceanBaseTLP {

    @Test
    public void testTLP() {
        assumeTrue(TestConfig.isEnvironmentTrue(TestConfig.OCEANBASE_ENV));
        assertEquals(0,
                Main.executeMain(new String[] { "--random-seed", "0", "--timeout-seconds", TestConfig.SECONDS,
                        "--num-threads", "4", "--database-prefix", "tlpdb", "--num-queries", TestConfig.NUM_QUERIES,
                        "--username", "sqlancer@test", "--password", "sqlancer",

                        "oceanbase", "--oracle", "TLP_WHERE" }));
    }

}
