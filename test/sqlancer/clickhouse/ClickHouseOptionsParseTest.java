package sqlancer.clickhouse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.beust.jcommander.JCommander;

class ClickHouseOptionsParseTest {

    @Test
    void defaultsLeaveRandomSessionSettingsOff() {
        ClickHouseOptions options = new ClickHouseOptions();
        JCommander.newBuilder().addObject(options).build().parse();
        assertFalse(options.randomSessionSettings);
        assertEquals(5, options.randomSessionSettingsBudget);
    }

    @Test
    void parsesRandomSessionSettingsFlags() {
        ClickHouseOptions options = new ClickHouseOptions();
        JCommander.newBuilder().addObject(options).build().parse("--random-session-settings", "true",
                "--random-session-settings-budget", "3");
        assertTrue(options.randomSessionSettings);
        assertEquals(3, options.randomSessionSettingsBudget);
    }

    @Test
    void parsesBudgetZeroForUnbounded() {
        ClickHouseOptions options = new ClickHouseOptions();
        JCommander.newBuilder().addObject(options).build().parse("--random-session-settings-budget", "0");
        assertEquals(0, options.randomSessionSettingsBudget);
    }

}
